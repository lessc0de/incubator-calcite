/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.materialize;

import org.apache.calcite.jdbc.CalcitePrepare;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.prepare.CalcitePrepareImpl;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.runtime.Utilities;
import org.apache.calcite.schema.Schemas;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.MaterializedViewTable;
import org.apache.calcite.schema.impl.StarTable;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlUtil;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.util.BitSets;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.graph.DefaultDirectedGraph;
import org.apache.calcite.util.graph.DefaultEdge;
import org.apache.calcite.util.graph.DirectedGraph;
import org.apache.calcite.util.graph.TopologicalOrderIterator;
import org.apache.calcite.util.mapping.IntPair;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Structure that allows materialized views based upon a star schema to be
 * recognized and recommended.
 */
public class Lattice {
  private static final Function<Column, String> GET_ALIAS =
      new Function<Column, String>() {
        public String apply(Column input) {
          return input.alias;
        }
      };

  private static final Function<Column, Integer> GET_ORDINAL =
      new Function<Column, Integer>() {
        public Integer apply(Column input) {
          return input.ordinal;
        }
      };

  public final ImmutableList<Node> nodes;
  public final ImmutableList<Column> columns;
  public final boolean auto;
  public final boolean algorithm;
  public final long algorithmMaxMillis;
  public final double rowCountEstimate;
  public final ImmutableList<Measure> defaultMeasures;
  public final ImmutableList<Tile> tiles;
  public final ImmutableList<String> uniqueColumnNames;

  private final Function<Integer, Column> toColumnFunction =
      new Function<Integer, Column>() {
        public Column apply(Integer input) {
          return columns.get(input);
        }
      };
  private final Function<AggregateCall, Measure> toMeasureFunction =
      new Function<AggregateCall, Measure>() {
        public Measure apply(AggregateCall input) {
          return new Measure(input.getAggregation(),
              Lists.transform(input.getArgList(), toColumnFunction));
        }
      };

  private Lattice(ImmutableList<Node> nodes, boolean auto, boolean algorithm,
      long algorithmMaxMillis,
      Double rowCountEstimate, ImmutableList<Column> columns,
      ImmutableList<Measure> defaultMeasures, ImmutableList<Tile> tiles) {
    this.nodes = Preconditions.checkNotNull(nodes);
    this.columns = Preconditions.checkNotNull(columns);
    this.auto = auto;
    this.algorithm = algorithm;
    this.algorithmMaxMillis = algorithmMaxMillis;
    this.defaultMeasures = Preconditions.checkNotNull(defaultMeasures);
    this.tiles = Preconditions.checkNotNull(tiles);

    // Validate that nodes form a tree; each node except the first references
    // a predecessor.
    for (int i = 0; i < nodes.size(); i++) {
      Node node = nodes.get(i);
      if (i == 0) {
        assert node.parent == null;
      } else {
        assert nodes.subList(0, i).contains(node.parent);
      }
    }

    List<String> nameList = Lists.newArrayList();
    for (Column column : columns) {
      nameList.add(column.alias);
    }
    uniqueColumnNames =
        ImmutableList.copyOf(
            SqlValidatorUtil.uniquify(Lists.transform(columns, GET_ALIAS)));
    if (rowCountEstimate == null) {
      // We could improve this when we fix
      // [CALCITE-429] Add statistics SPI for lattice optimization algorithm
      rowCountEstimate = 1000d;
    }
    Preconditions.checkArgument(rowCountEstimate > 0d);
    this.rowCountEstimate = rowCountEstimate;
  }

  /** Creates a Lattice. */
  public static Lattice create(CalciteSchema schema, String sql, boolean auto) {
    return builder(schema, sql).auto(auto).build();
  }

  private static void populateAliases(SqlNode from, List<String> aliases,
      String current) {
    if (from instanceof SqlJoin) {
      SqlJoin join = (SqlJoin) from;
      populateAliases(join.getLeft(), aliases, null);
      populateAliases(join.getRight(), aliases, null);
    } else if (from.getKind() == SqlKind.AS) {
      populateAliases(SqlUtil.stripAs(from), aliases,
          SqlValidatorUtil.getAlias(from, -1));
    } else {
      if (current == null) {
        current = SqlValidatorUtil.getAlias(from, -1);
      }
      aliases.add(current);
    }
  }

  private static boolean populate(List<RelNode> nodes, List<int[][]> tempLinks,
      RelNode rel) {
    if (nodes.isEmpty() && rel instanceof LogicalProject) {
      return populate(nodes, tempLinks, ((LogicalProject) rel).getInput());
    }
    if (rel instanceof TableScan) {
      nodes.add(rel);
      return true;
    }
    if (rel instanceof LogicalJoin) {
      LogicalJoin join = (LogicalJoin) rel;
      if (join.getJoinType() != JoinRelType.INNER) {
        throw new RuntimeException("only inner join allowed, but got "
            + join.getJoinType());
      }
      populate(nodes, tempLinks, join.getLeft());
      populate(nodes, tempLinks, join.getRight());
      for (RexNode rex : RelOptUtil.conjunctions(join.getCondition())) {
        tempLinks.add(grab(nodes, rex));
      }
      return true;
    }
    throw new RuntimeException("Invalid node type "
        + rel.getClass().getSimpleName() + " in lattice query");
  }

  /** Converts an "t1.c1 = t2.c2" expression into two (input, field) pairs. */
  private static int[][] grab(List<RelNode> leaves, RexNode rex) {
    switch (rex.getKind()) {
    case EQUALS:
      break;
    default:
      throw new AssertionError("only equi-join allowed");
    }
    final List<RexNode> operands = ((RexCall) rex).getOperands();
    return new int[][] {
        inputField(leaves, operands.get(0)),
        inputField(leaves, operands.get(1))};
  }

  /** Converts an expression into an (input, field) pair. */
  private static int[] inputField(List<RelNode> leaves, RexNode rex) {
    if (!(rex instanceof RexInputRef)) {
      throw new RuntimeException("only equi-join of columns allowed: " + rex);
    }
    RexInputRef ref = (RexInputRef) rex;
    int start = 0;
    for (int i = 0; i < leaves.size(); i++) {
      final RelNode leaf = leaves.get(i);
      final int end = start + leaf.getRowType().getFieldCount();
      if (ref.getIndex() < end) {
        return new int[] {i, ref.getIndex() - start};
      }
      start = end;
    }
    throw new AssertionError("input not found");
  }

  public String sql(ImmutableBitSet groupSet, List<Measure> aggCallList) {
    final ImmutableBitSet.Builder columnSetBuilder =
        ImmutableBitSet.builder(groupSet);
    for (Measure call : aggCallList) {
      for (Column arg : call.args) {
        columnSetBuilder.set(arg.ordinal);
      }
    }
    final ImmutableBitSet columnSet = columnSetBuilder.build();

    // Figure out which nodes are needed. Use a node if its columns are used
    // or if has a child whose columns are used.
    List<Node> usedNodes = Lists.newArrayList();
    for (Node node : nodes) {
      if (ImmutableBitSet.range(node.startCol, node.endCol)
          .intersects(columnSet)) {
        use(usedNodes, node);
      }
    }
    if (usedNodes.isEmpty()) {
      usedNodes.add(nodes.get(0));
    }
    final SqlDialect dialect = SqlDialect.DatabaseProduct.CALCITE.getDialect();
    final StringBuilder buf = new StringBuilder("SELECT ");
    final StringBuilder groupBuf = new StringBuilder("\nGROUP BY ");
    int k = 0;
    final Set<String> columnNames = Sets.newHashSet();
    for (int i : BitSets.toIter(groupSet)) {
      if (k++ > 0) {
        buf.append(", ");
        groupBuf.append(", ");
      }
      final Column column = columns.get(i);
      dialect.quoteIdentifier(buf, column.identifiers());
      dialect.quoteIdentifier(groupBuf, column.identifiers());
      final String fieldName = uniqueColumnNames.get(i);
      columnNames.add(fieldName);
      if (!column.alias.equals(fieldName)) {
        buf.append(" AS ");
        dialect.quoteIdentifier(buf, fieldName);
      }
    }
    if (groupSet.isEmpty()) {
      groupBuf.append("()");
    }
    int m = 0;
    for (Measure measure : aggCallList) {
      if (k++ > 0) {
        buf.append(", ");
      }
      buf.append(measure.agg.getName())
          .append("(");
      if (measure.args.isEmpty()) {
        buf.append("*");
      } else {
        int z = 0;
        for (Column arg : measure.args) {
          if (z++ > 0) {
            buf.append(", ");
          }
          dialect.quoteIdentifier(buf, arg.identifiers());
        }
      }
      buf.append(") AS ");
      String measureName;
      while (!columnNames.add(measureName = "m" + m)) {
        ++m;
      }
      dialect.quoteIdentifier(buf, measureName);
    }
    buf.append("\nFROM ");
    for (Node node : usedNodes) {
      if (node.parent != null) {
        buf.append("\nJOIN ");
      }
      dialect.quoteIdentifier(buf, node.scan.getTable().getQualifiedName());
      buf.append(" AS ");
      dialect.quoteIdentifier(buf, node.alias);
      if (node.parent != null) {
        buf.append(" ON ");
        k = 0;
        for (IntPair pair : node.link) {
          if (k++ > 0) {
            buf.append(" AND ");
          }
          final Column left = columns.get(node.parent.startCol + pair.source);
          dialect.quoteIdentifier(buf, left.identifiers());
          buf.append(" = ");
          final Column right = columns.get(node.startCol + pair.target);
          dialect.quoteIdentifier(buf, right.identifiers());
        }
      }
    }
    if (CalcitePrepareImpl.DEBUG) {
      System.out.println("Lattice SQL:\n" + buf);
    }
    buf.append(groupBuf);
    return buf.toString();
  }

  private static void use(List<Node> usedNodes, Node node) {
    if (!usedNodes.contains(node)) {
      if (node.parent != null) {
        use(usedNodes, node.parent);
      }
      usedNodes.add(node);
    }
  }

  public StarTable createStarTable() {
    final List<Table> tables = Lists.newArrayList();
    for (Node node : nodes) {
      tables.add(node.scan.getTable().unwrap(Table.class));
    }
    return StarTable.of(this, tables);
  }

  public static Builder builder(CalciteSchema calciteSchema, String sql) {
    return new Builder(calciteSchema, sql);
  }

  public List<Measure> toMeasures(List<AggregateCall> aggCallList) {
    return Lists.transform(aggCallList, toMeasureFunction);
  }

  public Iterable<? extends Tile> computeTiles() {
    if (!algorithm) {
      return tiles;
    }
    return new TileSuggester(this).tiles();
  }

  /** Returns an estimate of the number of rows in the un-aggregated star. */
  public double getFactRowCount() {
    return rowCountEstimate;
  }

  /** Returns an estimate of the number of rows in the tile with the given
   * dimensions. */
  public double getRowCount(List<Column> columns) {
    // The expected number of distinct values when choosing p values
    // with replacement from n integers is n . (1 - ((n - 1) / n) ^ p).
    //
    // If we have several uniformly distributed attributes A1 ... Am
    // with N1 ... Nm distinct values, they behave as one uniformly
    // distributed attribute with N1 * ... * Nm distinct values.
    BigInteger n = BigInteger.ONE;
    for (Column column : columns) {
      final int cardinality = cardinality(column);
      if (cardinality > 1) {
        n = n.multiply(BigInteger.valueOf(cardinality));
      }
    }
    final double nn = n.doubleValue();
    final double f = getFactRowCount();
    final double a = (nn - 1d) / nn;
    if (a == 1d) {
      // A under-flows if nn is large.
      return f;
    }
    final double v = nn * (1d - Math.pow(a, f));
    // Cap at fact-row-count, because numerical artifacts can cause it
    // to go a few % over.
    return Math.min(v, f);
  }

  public static final Map<String, Integer> CARDINALITY_MAP =
      ImmutableMap.<String, Integer>builder()
          .put("brand_name", 111)
          .put("cases_per_pallet", 10)
          .put("customer_id", 5581)
          .put("day_of_month", 30)
          .put("fiscal_period", 0)
          .put("gross_weight", 376)
          .put("low_fat", 2)
          .put("month_of_year", 12)
          .put("net_weight", 332)
          .put("product_category", 45)
          .put("product_class_id", 102)
          .put("product_department", 22)
          .put("product_family", 3)
          .put("product_id", 1559)
          .put("product_name", 1559)
          .put("product_subcategory", 102)
          .put("promotion_id", 149)
          .put("quarter", 4)
          .put("recyclable_package", 2)
          .put("shelf_depth", 488)
          .put("shelf_height", 524)
          .put("shelf_width", 534)
          .put("SKU", 1559)
          .put("SRP", 315)
          .put("store_cost", 10777)
          .put("store_id", 13)
          .put("store_sales", 1049)
          .put("the_date", 323)
          .put("the_day", 7)
          .put("the_month", 12)
          .put("the_year", 1)
          .put("time_id", 323)
          .put("units_per_case", 36)
          .put("unit_sales", 6)
          .put("week_of_year", 52)
          .build();

  private int cardinality(Column column) {
    final Integer integer = CARDINALITY_MAP.get(column.alias);
    if (integer != null && integer > 0) {
      return integer;
    }
    return column.alias.length();
  }

  /** Source relation of a lattice.
   *
   * <p>Relations form a tree; all relations except the root relation
   * (the fact table) have precisely one parent and an equi-join
   * condition on one or more pairs of columns linking to it. */
  public static class Node {
    public final TableScan scan;
    public final Node parent;
    public final ImmutableList<IntPair> link;
    public final int startCol;
    public final int endCol;
    public final String alias;

    public Node(TableScan scan, Node parent, List<IntPair> link,
        int startCol, int endCol, String alias) {
      this.scan = Preconditions.checkNotNull(scan);
      this.parent = parent;
      this.link = link == null ? null : ImmutableList.copyOf(link);
      assert (parent == null) == (link == null);
      assert startCol >= 0;
      assert endCol > startCol;
      this.startCol = startCol;
      this.endCol = endCol;
      this.alias = alias;
    }
  }

  /** Edge in the temporary graph. */
  private static class Edge extends DefaultEdge {
    public static final DirectedGraph.EdgeFactory<RelNode, Edge> FACTORY =
        new DirectedGraph.EdgeFactory<RelNode, Edge>() {
          public Edge createEdge(RelNode source, RelNode target) {
            return new Edge(source, target);
          }
        };

    final List<IntPair> pairs = Lists.newArrayList();

    public Edge(RelNode source, RelNode target) {
      super(source, target);
    }

    public RelNode getTarget() {
      return (RelNode) target;
    }

    public RelNode getSource() {
      return (RelNode) source;
    }
  }

  /** Measure in a lattice. */
  public static class Measure implements Comparable<Measure> {
    public final SqlAggFunction agg;
    public final ImmutableList<Column> args;

    public Measure(SqlAggFunction agg, Iterable<Column> args) {
      this.agg = Preconditions.checkNotNull(agg);
      this.args = ImmutableList.copyOf(args);
    }

    public int compareTo(Measure measure) {
      int c = agg.getName().compareTo(measure.agg.getName());
      if (c != 0) {
        return c;
      }
      return compare(args, measure.args);
    }

    @Override public String toString() {
      return "Measure: [agg: " + agg + ", args: " + args + "]";
    }

    @Override public int hashCode() {
      return com.google.common.base.Objects.hashCode(agg, args);
    }

    @Override public boolean equals(Object obj) {
      return obj == this
          || obj instanceof Measure
          && this.agg.equals(((Measure) obj).agg)
          && this.args.equals(((Measure) obj).args);
    }

    /** Returns the set of distinct argument ordinals. */
    public ImmutableBitSet argBitSet() {
      final ImmutableBitSet.Builder bitSet = ImmutableBitSet.builder();
      for (Column arg : args) {
        bitSet.set(arg.ordinal);
      }
      return bitSet.build();
    }

    /** Returns a list of argument ordinals. */
    public List<Integer> argOrdinals() {
      return Lists.transform(args, GET_ORDINAL);
    }

    private static int compare(List<Column> list0, List<Column> list1) {
      final int size = Math.min(list0.size(), list1.size());
      for (int i = 0; i < size; i++) {
        final int o0 = list0.get(i).ordinal;
        final int o1 = list1.get(i).ordinal;
        final int c = Utilities.compare(o0, o1);
        if (c != 0) {
          return c;
        }
      }
      return Utilities.compare(list0.size(), list1.size());
    }
  }

  /** Column in a lattice. Columns are identified by table alias and
   * column name, and may have an additional alias that is unique
   * within the entire lattice. */
  public static class Column implements Comparable<Column> {
    public final int ordinal;
    public final String table;
    public final String column;
    public final String alias;

    private Column(int ordinal, String table, String column, String alias) {
      this.ordinal = ordinal;
      this.table = Preconditions.checkNotNull(table);
      this.column = Preconditions.checkNotNull(column);
      this.alias = Preconditions.checkNotNull(alias);
    }

    public int compareTo(Column column) {
      return Utilities.compare(ordinal, column.ordinal);
    }

    @Override public int hashCode() {
      return ordinal;
    }

    @Override public boolean equals(Object obj) {
      return obj == this
          || obj instanceof Column
          && this.ordinal == ((Column) obj).ordinal;
    }

    @Override public String toString() {
      return identifiers().toString();
    }

    public List<String> identifiers() {
      return ImmutableList.of(table, column);
    }
  }

  /** Lattice builder. */
  public static class Builder {
    private final List<Node> nodes = Lists.newArrayList();
    private final ImmutableList<Column> columns;
    private final ImmutableListMultimap<String, Column> columnsByAlias;
    private final ImmutableList.Builder<Measure> defaultMeasureListBuilder =
        ImmutableList.builder();
    private final ImmutableList.Builder<Tile> tileListBuilder =
        ImmutableList.builder();
    private boolean algorithm = false;
    private long algorithmMaxMillis = -1;
    private boolean auto = true;
    private Double rowCountEstimate;

    public Builder(CalciteSchema schema, String sql) {
      CalcitePrepare.ConvertResult parsed =
          Schemas.convert(MaterializedViewTable.MATERIALIZATION_CONNECTION,
              schema, schema.path(null), sql);

      // Walk the join tree.
      List<RelNode> relNodes = Lists.newArrayList();
      List<int[][]> tempLinks = Lists.newArrayList();
      populate(relNodes, tempLinks, parsed.relNode);

      // Get aliases.
      List<String> aliases = Lists.newArrayList();
      populateAliases(((SqlSelect) parsed.sqlNode).getFrom(), aliases, null);

      // Build a graph.
      final DirectedGraph<RelNode, Edge> graph =
          DefaultDirectedGraph.create(Edge.FACTORY);
      for (RelNode node : relNodes) {
        graph.addVertex(node);
      }
      for (int[][] tempLink : tempLinks) {
        final RelNode source = relNodes.get(tempLink[0][0]);
        final RelNode target = relNodes.get(tempLink[1][0]);
        Edge edge = graph.getEdge(source, target);
        if (edge == null) {
          edge = graph.addEdge(source, target);
        }
        edge.pairs.add(IntPair.of(tempLink[0][1], tempLink[1][1]));
      }

      // Convert the graph into a tree of nodes, each connected to a parent and
      // with a join condition to that parent.
      Node previous = null;
      final Map<RelNode, Node> map = Maps.newIdentityHashMap();
      int previousColumn = 0;
      for (RelNode relNode : TopologicalOrderIterator.of(graph)) {
        final List<Edge> edges = graph.getInwardEdges(relNode);
        Node node;
        final int column = previousColumn
            + relNode.getRowType().getFieldCount();
        if (previous == null) {
          if (!edges.isEmpty()) {
            throw new RuntimeException("root node must not have relationships: "
                + relNode);
          }
          node = new Node((TableScan) relNode, null, null,
              previousColumn, column, aliases.get(nodes.size()));
        } else {
          if (edges.size() != 1) {
            throw new RuntimeException(
                "child node must have precisely one parent: " + relNode);
          }
          final Edge edge = edges.get(0);
          node = new Node((TableScan) relNode,
              map.get(edge.getSource()), edge.pairs, previousColumn, column,
              aliases.get(nodes.size()));
        }
        nodes.add(node);
        map.put(relNode, node);
        previous = node;
        previousColumn = column;
      }

      final ImmutableList.Builder<Column> builder = ImmutableList.builder();
      final ImmutableListMultimap.Builder<String, Column> aliasBuilder =
          ImmutableListMultimap.builder();
      int c = 0;
      for (Node node : nodes) {
        if (node.scan != null) {
          for (String name : node.scan.getRowType().getFieldNames()) {
            final Column column = new Column(c++, node.alias, name, name);
            builder.add(column);
            aliasBuilder.put(column.alias, column);
          }
        }
      }
      columns = builder.build();
      columnsByAlias = aliasBuilder.build();
    }

    /** Sets the "auto" attribute (default true). */
    public Builder auto(boolean auto) {
      this.auto = auto;
      return this;
    }

    /** Sets the "algorithm" attribute (default false). */
    public Builder algorithm(boolean algorithm) {
      this.algorithm = algorithm;
      return this;
    }

    /** Sets the "algorithmMaxMillis" attribute (default -1). */
    public Builder algorithmMaxMillis(long algorithmMaxMillis) {
      this.algorithmMaxMillis = algorithmMaxMillis;
      return this;
    }

    /** Sets the "rowCountEstimate" attribute (default null). */
    public Builder rowCountEstimate(double rowCountEstimate) {
      this.rowCountEstimate = rowCountEstimate;
      return this;
    }

    /** Builds a lattice. */
    public Lattice build() {
      return new Lattice(ImmutableList.copyOf(nodes), auto, algorithm,
          algorithmMaxMillis, rowCountEstimate, columns,
          defaultMeasureListBuilder.build(), tileListBuilder.build());
    }

    /** Resolves the arguments of a
     * {@link org.apache.calcite.model.JsonMeasure}. They must either be null,
     * a string, or a list of strings. Throws if the structure is invalid, or if
     * any of the columns do not exist in the lattice. */
    public ImmutableList<Column> resolveArgs(Object args) {
      if (args == null) {
        return ImmutableList.of();
      } else if (args instanceof String) {
        return ImmutableList.of(resolveColumnByAlias((String) args));
      } else if (args instanceof List) {
        final ImmutableList.Builder<Column> builder = ImmutableList.builder();
        for (Object o : (List) args) {
          if (o instanceof String) {
            builder.add(resolveColumnByAlias((String) o));
          } else {
            throw new RuntimeException(
                "Measure arguments must be a string or a list of strings; argument: "
                    + o);
          }
        }
        return builder.build();
      } else {
        throw new RuntimeException(
            "Measure arguments must be a string or a list of strings");
      }
    }

    /** Looks up a column in this lattice by alias. The alias must be unique
     * within the lattice.
     */
    private Column resolveColumnByAlias(String name) {
      final ImmutableList<Column> list = columnsByAlias.get(name);
      if (list == null || list.size() == 0) {
        throw new RuntimeException("Unknown lattice column '" + name + "'");
      } else if (list.size() == 1) {
        return list.get(0);
      } else {
        throw new RuntimeException("Lattice column alias '" + name
            + "' is not unique");
      }
    }

    public Column resolveColumn(Object name) {
      if (name instanceof String) {
        return resolveColumnByAlias((String) name);
      }
      if (name instanceof List) {
        List list = (List) name;
        switch (list.size()) {
        case 1:
          final Object alias = list.get(0);
          if (alias instanceof String) {
            return resolveColumnByAlias((String) alias);
          }
          break;
        case 2:
          final Object table = list.get(0);
          final Object column = list.get(1);
          if (table instanceof String && column instanceof String) {
            return resolveQualifiedColumn((String) table, (String) column);
          }
          break;
        }
      }
      throw new RuntimeException(
          "Lattice column reference must be a string or a list of 1 or 2 strings; column: "
              + name);
    }

    private Column resolveQualifiedColumn(String table, String column) {
      for (Column column1 : columns) {
        if (column1.table.equals(table)
            && column1.column.equals(column)) {
          return column1;
        }
      }
      throw new RuntimeException("Unknown lattice column [" + table + ", "
          + column + "]");
    }

    public Measure resolveMeasure(String aggName, Object args) {
      final SqlAggFunction agg = resolveAgg(aggName);
      final ImmutableList<Column> list = resolveArgs(args);
      return new Measure(agg, list);
    }

    private SqlAggFunction resolveAgg(String aggName) {
      if (aggName.equalsIgnoreCase("count")) {
        return SqlStdOperatorTable.COUNT;
      } else if (aggName.equalsIgnoreCase("sum")) {
        return SqlStdOperatorTable.SUM;
      } else {
        throw new RuntimeException("Unknown lattice aggregate function "
            + aggName);
      }
    }

    public void addMeasure(Measure measure) {
      defaultMeasureListBuilder.add(measure);
    }

    public void addTile(Tile tile) {
      tileListBuilder.add(tile);
    }
  }

  /** Materialized aggregate within a lattice. */
  public static class Tile {
    public final ImmutableList<Measure> measures;
    public final ImmutableList<Column> dimensions;
    public final ImmutableBitSet bitSet;

    public Tile(ImmutableList<Measure> measures,
        ImmutableList<Column> dimensions) {
      this.measures = measures;
      this.dimensions = dimensions;
      assert Ordering.natural().isStrictlyOrdered(dimensions);
      assert Ordering.natural().isStrictlyOrdered(measures);
      final ImmutableBitSet.Builder bitSetBuilder = ImmutableBitSet.builder();
      for (Column dimension : dimensions) {
        bitSetBuilder.set(dimension.ordinal);
      }
      bitSet = bitSetBuilder.build();
    }

    public static TileBuilder builder() {
      return new TileBuilder();
    }

    public ImmutableBitSet bitSet() {
      return bitSet;
    }
  }

  /** Tile builder. */
  public static class TileBuilder {
    private final List<Measure> measureBuilder = Lists.newArrayList();
    private final List<Column> dimensionListBuilder = Lists.newArrayList();

    public Tile build() {
      return new Tile(
          Ordering.natural().immutableSortedCopy(measureBuilder),
          Ordering.natural().immutableSortedCopy(dimensionListBuilder));
    }

    public void addMeasure(Measure measure) {
      measureBuilder.add(measure);
    }

    public void addDimension(Column column) {
      dimensionListBuilder.add(column);
    }
  }
}

// End Lattice.java
