///////////////////////////////////////////////////////////////////////////////
// For information as to what this class does, see the Javadoc, below.       //
// Copyright (c) 1998, 1999, 2000, 2001, 2002, 2003, 2004, 2005, 2006,       //
// 2007, 2008, 2009, 2010, 2014, 2015 by Peter Spirtes, Richard Scheines, Joseph   //
// Ramsey, and Clark Glymour.                                                //
//                                                                           //
// This program is free software; you can redistribute it and/or modify      //
// it under the terms of the GNU General Public License as published by      //
// the Free Software Foundation; either version 2 of the License, or         //
// (at your option) any later version.                                       //
//                                                                           //
// This program is distributed in the hope that it will be useful,           //
// but WITHOUT ANY WARRANTY; without even the implied warranty of            //
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the             //
// GNU General Public License for more details.                              //
//                                                                           //
// You should have received a copy of the GNU General Public License         //
// along with this program; if not, write to the Free Software               //
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA //
///////////////////////////////////////////////////////////////////////////////

package edu.cmu.tetrad.search;

import edu.cmu.tetrad.data.*;
import edu.cmu.tetrad.graph.*;
import edu.cmu.tetrad.regression.RegressionDataset;
import edu.cmu.tetrad.util.*;
import org.apache.commons.math3.distribution.TDistribution;
import org.apache.commons.math3.linear.SingularMatrixException;

import java.util.List;
import java.util.*;

import static edu.cmu.tetrad.util.StatUtils.max;
import static edu.cmu.tetrad.util.StatUtils.min;
import static edu.cmu.tetrad.util.StatUtils.*;
import static java.lang.Math.max;
import static java.lang.Math.*;

/**
 * Fast adjacency search followed by robust skew orientation. Checks are done for adding
 * two-cycles. The two-cycle checks do not require non-Gaussianity. The robust skew
 * orientation of edges left or right does.
 *
 * @author Joseph Ramsey
 */
public final class Fask_C implements GraphSearch {

    // An initial graph to orient, skipping the adjacency step.
    private Graph initialGraph = null;

    // The data sets being analyzed. They must all have the same variables and the same
    // number of records.
    private DataSet dataSet;

    private int depth = 1000;

    // Knowledge the the search will obey, of forbidden and required edges.
    private IKnowledge knowledge = new Knowledge2();

    // Data as a double[][].
    private final double[][] colData;

    // A threshold for including extra adjacencies due to skewness.
    private double twoCycleAlpha = 0.05;

    // Two cycle twoCycleCutoff.
    private double twoCycleCutoff;

    // Penalty discount
    private double penaltyDiscount = 1;

    private boolean faithfulnessAssumed = true;

    private boolean symmetricFirstStep = false;

    private int maxDegree = -1;

    // The list of variables.
    private final List<Node> variables;

    // True iff verbose output should be printed.
    private boolean verbose = false;

    // The maximum number it iterations the loop should go through for all edges.
    private int maxIterations = 15;

    private RegressionDataset regressionDataset;


    /**
     * @param dataSet These datasets must all have the same variables, in the same order.
     */
    public Fask_C(DataSet dataSet) {
        dataSet = DataUtils.getNonparanormalTransformed(dataSet);
        this.dataSet = DataUtils.center(dataSet);
        colData = dataSet.getDoubleData().transpose().toArray();
        this.variables = dataSet.getVariables();

        if (isVerbose()) {
            for (Node variable : variables) {
                TetradLogger.getInstance().forceLogMessage(variable + " skewness = " + skewness(variable));
            }
        }

        regressionDataset = new RegressionDataset(dataSet);
    }

    //======================================== PUBLIC METHODS ====================================//

    /**
     * Runs the search on the concatenated data, returning a graph, possibly cyclic, possibly with
     * two-cycles. Runs the fast adjacency search (FAS, Spirtes et al., 2000) follows by a modification
     * of the robust skew rule (Pairwise Likelihood Ratios for Estimation of Non-Gaussian Structural
     * Equation Models, Smith and Hyvarinen), together with some heuristics for orienting two-cycles.
     *
     * @return the graph. Some of the edges may be undirected (though it shouldn't be many in most cases)
     * and some of the adjacencies may be two-cycles.
     */
    public Graph search() {
        TetradLogger.getInstance().forceLogMessage("\nStarting FASK-B Algorithm");

        TetradLogger.getInstance().forceLogMessage("\nSmoothly skewed:");

        for (Node node : dataSet.getVariables()) {
            if (smoothlySkewed(node)) {
                TetradLogger.getInstance().forceLogMessage(node.getName());
            }
        }

        TetradLogger.getInstance().forceLogMessage("\nNot smoothly skewed:");

        for (Node node : dataSet.getVariables()) {
            if (!smoothlySkewed(node)) {
                TetradLogger.getInstance().forceLogMessage(node.getName());
            }
        }

        TetradLogger.getInstance().forceLogMessage("");

        this.twoCycleCutoff = cutoff(getTwoCycleAlpha());

        List<Node> variables = dataSet.getVariables();
        Graph graph = new EdgeListGraph(variables);
        dataSet = DataUtils.center(dataSet);
        double[][] data = dataSet.getDoubleData().transpose().toArray();

        {
            if (initialGraph == null) {
                SemBicScore score = new SemBicScore(new CovarianceMatrixOnTheFly(dataSet));
                score.setPenaltyDiscount(getPenaltyDiscount());

                edu.cmu.tetrad.search.Fges search
                        = new edu.cmu.tetrad.search.Fges(score);
                search.setFaithfulnessAssumed(faithfulnessAssumed);
                search.setKnowledge(knowledge);
                search.setVerbose(verbose);
                search.setMaxDegree(maxDegree);
                search.setSymmetricFirstStep(symmetricFirstStep);

                initialGraph = search.search();
            } else {
                initialGraph = GraphUtils.undirectedGraph(initialGraph);
                initialGraph = GraphUtils.replaceNodes(initialGraph, dataSet.getVariables());

                if (initialGraph == null) throw new NullPointerException("Initial graph is null.");
            }
        }

        for (Edge edge : initialGraph.getEdges()) {
            Node X = edge.getNode1();
            Node Y = edge.getNode2();

            if (edgeForbiddenByKnowledge(X, Y)) {
                // Don't add an edge.
            } else if (knowledgeOrients(X, Y)) {
                initialGraph.removeEdges(X, Y);
                initialGraph.addDirectedEdge(X, Y);
            } else if (knowledgeOrients(Y, X)) {
                initialGraph.removeEdges(Y, X);
                initialGraph.addDirectedEdge(Y, X);
            }
        }

        Set<Node> changed1 = new HashSet<>(variables);
        Set<Node> changed2 = new HashSet<>(variables);
        for (Edge edge : initialGraph.getEdges()) graph.addEdge(edge);


        for (Edge edge : graph.getEdges()) {
            Node X = edge.getNode1();
            Node Y = edge.getNode2();
            orientEdge(variables, graph, data, changed2, X, Y, new ArrayList<>(), new ArrayList<>());
        }

        {
            for (int d = 0; d < getMaxIterations(); d++) {
                if (changed1.isEmpty()) break;

                changed1 = changed2;
                changed2 = new HashSet<>();

                for (Edge edge : graph.getEdges()) {
                    Node X, Y;

                    if (Edges.isUndirectedEdge(edge) || Edges.isBidirectedEdge(edge)) {
                        X = edge.getNode1();
                        Y = edge.getNode2();
                        if (!(changed1.contains(X) || changed1.contains(Y))) continue;
                    } else {
                        X = Edges.getDirectedEdgeTail(edge);
                        Y = Edges.getDirectedEdgeHead(edge);
                        if (!changed1.contains(Y)) continue;
                    }


                    orientEdge(variables, graph, data, changed2, X, Y, graph.getParents(X), graph.getParents(Y));
                }
            }
        }

        System.out.println("graph = " + graph);

        TetradLogger.getInstance().forceLogMessage("\nOrienting 2-cycles or confounders");
        for (Edge edge : graph.getEdges()) {
            Node X = edge.getNode1();
            Node Y = edge.getNode2();

            if (edgeForbiddenByKnowledge(X, Y)) continue;

            if (graph.isAdjacentTo(X, Y) && Edges.isUndirectedEdge(graph.getEdge(X, Y))
                    && !knowledgeOrients(X, Y) && !knowledgeOrients(Y, X)
                    && twocycle(X, Y, graph)) {
                System.out.println("2-cycle or confounder: " + X + "<->" + Y);
                graph.removeEdges(X, Y);
                graph.addDirectedEdge(X, Y);
                graph.addDirectedEdge(Y, X);
            }
        }

//        for (Edge edge : graph.getEdges()) {
//            if (Edges.isUndirectedEdge(edge)) {
//                graph.removeEdge(edge);
//            }
//        }

        System.out.println("graph = " + graph);

        return graph;
    }

    private void orientEdge(List<Node> variables, Graph graph, double[][] data, Set<Node> changed2,
                            Node X, Node Y, List<Node> Zx, List<Node> Zy) {
        if (!edgeForbiddenByKnowledge(X, Y) && !knowledgeOrients(X, Y) && !knowledgeOrients(Y, X)) {
            Zx.remove(Y);
            Zy.remove(X);

            removeTier1(Zx);
            removeTier1(Zy);

            int i = variables.indexOf(X);
            int j = variables.indexOf(Y);

            double[] x = data[i];
            double[] y = data[j];

            double[][] zy = new double[Zy.size()][];

            for (int t = 0; t < Zy.size(); t++) {
                final Node V = Zy.get(t);
                zy[t] = data[variables.indexOf(V)];
            }

            double[][] zx = new double[Zx.size()][];

            for (int t = 0; t < Zx.size(); t++) {
                final Node V = Zx.get(t);
                zx[t] = data[variables.indexOf(V)];
            }

            System.out.println("X = " + X + " Y = " + Y + " | Zy = " + Zy);

            final boolean cxy = leftright(x, y, zy) > 0;
            final boolean cyx = leftright(y, x, zx) > 0;
//
//                        final boolean cxy = leftright2(X, Y, Zy);
//                        final boolean cyx = leftright2(Y, X, Zx);
//
//                        boolean cxy = leftRightMinnesota(x, y);
//                        boolean cyx = leftRightMinnesota(y, x);

            if (cxy && !cyx && !graph.getEdge(X, Y).pointsTowards(Y)) {
                graph.removeEdges(X, Y);
                graph.addDirectedEdge(X, Y);
                changed2.add(Y);
            } else if (cyx && !cxy && !graph.getEdge(Y, X).pointsTowards(X)) {
                graph.removeEdges(Y, X);
                graph.addDirectedEdge(Y, X);
                changed2.add(X);
            }
            else if (!cyx && !cxy && !Edges.isBidirectedEdge(graph.getEdge(Y, X))) {
                graph.removeEdges(X, Y);
                graph.addBidirectedEdge(X, Y);
                changed2.add(X);
                changed2.add(Y);
            }
            else if (!cyx && !cxy && !Edges.isUndirectedEdge(graph.getEdge(Y, X))) {
                graph.removeEdges(X, Y);
                graph.addUndirectedEdge(X, Y);
                changed2.add(X);
                changed2.add(Y);
            }
        }
    }

    private void removeTier1(List<Node> Zx) {
        for (Node z : new ArrayList<>(Zx)) {
            if (knowledge.getNumTiers() > 1 && knowledge.getTier(1).contains(z.getName())) {
                Zx.remove(z);
            }
        }
    }

    private boolean isAdj(double[] x, double[] y) {
        double c1 = StatUtils.cov(x, y, x, 0, +1)[1];
        double c2 = StatUtils.cov(x, y, y, 0, +1)[1];

        double d1 = (covariance(x, y) / variance(x)) * (StatUtils.cov(x, x, x, 0, +1)[0]
                - StatUtils.cov(x, x, y, 0, +1)[0]);
        double d2 = (covariance(x, y) / variance(y)) * (StatUtils.cov(y, y, y, 0, +1)[0]
                - StatUtils.cov(y, y, x, 0, +1)[0]);

        System.out.println("d1 = " + d1 + " d2 = " + d2);

        double d3 = Math.max(abs(d1), abs(d2));

        return abs(c1 - c2) > .3;//  d3 + 0;
    }

    private double leftright(double[] x, double[] y, double[]... z) {
        double[][] cond = new double[z.length + 1][];
        cond[0] = x;
        System.arraycopy(z, 0, cond, 1, z.length);
        double[] ry = residuals(y, cond);
        double a = covariance(x, y) / variance(x);

        return E(a, x, ry, +1) - E(a, x, ry, -1);
    }



    private boolean leftright2(Node X, Node Y, List<Node> Z) {
//        boolean b1 = false, b2 = false;
        double p1 = 0, p2 = 0;

        try {
            E hx = new E(X, Y, Z, null).invoke();
            E hy = new E(X, Y, Z, X).invoke();

            double[] dx = hx.getR();
            double[] dy = hy.getR();

            double nx = hx.getRows().size();
            double ny = hy.getRows().size();

            // Welch's Test
            double exyy = variance(dy) / ny;
            double exyx = variance(dx) / nx;
            double t = (mean(dy) - mean(dx)) / sqrt(exyy + exyx);
            double df = ((exyy + exyx) * (exyy + exyx)) / ((exyy * exyy) / (ny - 1)) + ((exyx * exyx) / (nx - 1));

            p1 = 2.0 * (1.0 - new TDistribution(df).cumulativeProbability(abs(t)));
//            b1 = p1 < getTwoCycleAlpha();
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            E hy = new E(Y, X, Z, null).invoke();
            E hx = new E(Y, X, Z, Y).invoke();

            double[] dx = hx.getR();
            double[] dy = hy.getR();

            double nx = hx.getRows().size();
            double ny = hy.getRows().size();

            // Welch's Test
            double exyy = variance(dy) / ny;
            double exyx = variance(dx) / nx;
            double t = (mean(dx) - mean(dy)) / sqrt(exyy + exyx);
            double df = ((exyy + exyx) * (exyy + exyx)) / ((exyy * exyy) / (ny - 1)) + ((exyx * exyx) / (nx - 1));

            p2 = 2.0 * (1.0 - new TDistribution(df).cumulativeProbability(abs(t)));
//            b2 = p2 < getTwoCycleAlpha();
        } catch (Exception e) {
            e.printStackTrace();
        }

//        return !b1 && b2;
        return p1 < p2;
    }

    public Graph getInitialGraph() {
        return initialGraph;
    }

    public void setInitialGraph(Graph initialGraph) {
        this.initialGraph = initialGraph;
    }

    /**
     * @return The depth of search for the Fast Adjacency Search (FAS).
     */

    public int getDepth() {
        return depth;
    }

    /**
     * @param depth The depth of search for the Fast Adjacency Search (S). The default is -1.
     *              unlimited. Making this too high may results in statistical errors.
     */
    public void setDepth(int depth) {
        this.depth = depth;
    }

    /**
     * @return The elapsed time in milliseconds.
     */
    public long getElapsedTime() {
        return (long) 0;
    }

    /**
     * @return the current knowledge.
     */
    public IKnowledge getKnowledge() {
        return knowledge;
    }

    /**
     * @param knowledge Knowledge of forbidden and required edges.
     */
    public void setKnowledge(IKnowledge knowledge) {
        this.knowledge = knowledge;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    /////////////////////////////////////// PRIVATE METHODS ////////////////////////////////////

    private boolean smoothlySkewed(Node X) {
        double[] x = colData[variables.indexOf(X)];
        return smoothlySkewed(x);
    }

    private boolean smoothlySkewed(double[] x) {
        x = Arrays.copyOf(x, x.length);
        Arrays.sort(x);

        double min = min(x);
        double max = max(x);

        double _max = max(abs(min), abs(max));

        boolean smoothPositive = true;
        boolean smoothNegative = true;

        int numIntervals = 5;

        for (int i = 0; i < numIntervals; i++) {
            double t = (i * _max) / numIntervals;

            double a1 = Integrator.getArea(x1 -> 0, -t, 0, 5);
            double a2 = Integrator.getArea(x2 -> 0, 0, t, 5);

            if (a1 < a2) {
                smoothPositive = false;
            }

            if (a1 > a2) {
                smoothNegative = false;
            }
        }

        return smoothPositive || smoothNegative;
    }

    private double skewness(Node X) {
        double[] x = colData[variables.indexOf(X)];
        return StatUtils.skewness(x);
    }

    private double[] residuals(double[] _y, double[][] _x) {
        TetradMatrix y = new TetradMatrix(new double[][]{_y}).transpose();
        TetradMatrix x = new TetradMatrix(_x).transpose();

        TetradMatrix xT = x.transpose();
        TetradMatrix xTx = xT.times(x);
        TetradMatrix xTxInv = xTx.inverse();
        TetradMatrix xTy = xT.times(y);
        TetradMatrix b = xTxInv.times(xTy);

        TetradMatrix yHat = x.times(b);
        if (yHat.columns() == 0) yHat = y.copy();

        return y.minus(yHat).getColumn(0).toArray();
    }

    private static double E(double a, double[] x, double[] ry, double dir) {
        double exy = 0.0;

        System.out.println(" skew = " + StatUtils.skewness(ry));

        int n = 0;

        for (int k = 0; k < x.length; k++) {
            final double _x = x[k];
            final double _ry = ry[k];

            final double _y = abs(a) * _x + _ry;

            if (_x * dir > 0 && _y * dir < 0) {
                exy += _x * _ry;
                n++;
            }
        }

        return exy / n;
    }

    private boolean leftRightMinnesota(double[] x, double[] y) {
        x = correctSkewness(x);
        y = correctSkewness(y);

        final double cxyx = cov(x, y, x);
        final double cxyy = cov(x, y, y);
        final double cxxx = cov(x, x, x);
        final double cyyx = cov(y, y, x);
        final double cxxy = cov(x, x, y);
        final double cyyy = cov(y, y, y);

        double a1 = cxyx / cxxx;
        double a2 = cxyy / cxxy;
        double b1 = cxyy / cyyy;
        double b2 = cxyx / cyyx;

        double Q = (a2 > 0) ? a1 / a2 : a2 / a1;
        double R = (b2 > 0) ? b1 / b2 : b2 / b1;

        double lr = Q - R;

//        if (StatUtils.correlation(x, y) < 0) lr += delta;

        final double sk_ey = StatUtils.skewness(residuals(y, new double[][]{x}));

        if (sk_ey < 0) {
            lr *= -1;
        }

        final double a = correlation(x, y);

        if (a < 0 && sk_ey > -.2) {
            lr *= -1;
        }

        return lr > 0;
    }

    private static double cov(double[] x, double[] y, double[] condition) {
        double exy = 0.0;

        int n = 0;

        for (int k = 0; k < x.length; k++) {
            if (condition[k] > 0) {
                exy += x[k] * y[k];
                n++;
            }
        }

        return exy / n;
    }

    private double[] correctSkewness(double[] data) {
        double skewness = StatUtils.skewness(data);
        double[] data2 = new double[data.length];
        for (int i = 0; i < data.length; i++) data2[i] = data[i] * Math.signum(skewness);
        return data2;
    }

//    private boolean bidirected(Node X, Node Y, Graph G0) {
//        double[] x = colData[variables.indexOf(X)];
//        double[] y = colData[variables.indexOf(Y)];
//
//        Set<Node> adjSet = new HashSet<>(G0.getAdjacentNodes(X));
//        adjSet.addAll(G0.getAdjacentNodes(Y));
//        List<Node> adj = new ArrayList<>(adjSet);
//        adj.remove(X);
//        adj.remove(Y);
//
//        DepthChoiceGenerator gen = new DepthChoiceGenerator(adj.size(), Math.min(depth, adj.size()));
//        int[] choice;
//
//        while ((choice = gen.next()) != null) {
//            List<Node> _adj = GraphUtils.asList(choice, adj);
//            double[][] _Z = new double[_adj.size()][];
//
//            for (int f = 0; f < _adj.size(); f++) {
//                Node _z = _adj.get(f);
//                int column = dataSet.getColumn(_z);
//                _Z[f] = colData[column];
//            }
//
//            double pc = 0;
//            double pc1 = 0;
//            double pc2 = 0;
//
//            try {
//                pc = partialCorrelation(x, y, _Z, x, Double.NEGATIVE_INFINITY, +1);
//                pc1 = partialCorrelation(x, y, _Z, x, 0, +1);
//                pc2 = partialCorrelation(x, y, _Z, y, 0, +1);
//            } catch (SingularMatrixException e) {
//                System.out.println("Singularity X = " + X + " Y = " + Y + " adj = " + adj);
//                TetradLogger.getInstance().log("info", "Singularity X = " + X + " Y = " + Y + " adj = " + adj);
//                continue;
//            }
//
//            int nc = StatUtils.getRows(x, Double.NEGATIVE_INFINITY, +1).size();
//            int nc1 = StatUtils.getRows(x, 0, +1).size();
//            int nc2 = StatUtils.getRows(y, 0, +1).size();
//
//            double z = 0.5 * (log(1.0 + pc) - log(1.0 - pc));
//            double z1 = 0.5 * (log(1.0 + pc1) - log(1.0 - pc1));
//            double z2 = 0.5 * (log(1.0 + pc2) - log(1.0 - pc2));
//
//            double zv1 = (z - z1) / sqrt((1.0 / ((double) nc - 3) + 1.0 / ((double) nc1 - 3)));
//            double zv2 = (z - z2) / sqrt((1.0 / ((double) nc - 3) + 1.0 / ((double) nc2 - 3)));
//
//            boolean rejected1 = abs(zv1) > twoCycleCutoff;
//            boolean rejected2 = abs(zv2) > twoCycleCutoff;
//
//            boolean possibleTwoCycle = false;
//
//            if (zv1 < 0 && zv2 > 0 && rejected1) {
//                possibleTwoCycle = true;
//            } else if (zv1 > 0 && zv2 < 0 && rejected2) {
//                possibleTwoCycle = true;
//            } else if (rejected1 && rejected2) {
//                possibleTwoCycle = true;
//            }
//
//            if (!possibleTwoCycle) {
//                return false;
//            }
//        }
//
//        return true;
//    }

    private double partialCorrelation(double[] x, double[] y, double[][] z, double[] condition, double threshold, double direction) throws SingularMatrixException {
        double[][] cv = StatUtils.covMatrix(x, y, z, condition, threshold, direction);
        TetradMatrix m = new TetradMatrix(cv).transpose();
        return StatUtils.partialCorrelation(m);
    }

    private double cutoff(double alpha) {
        if (alpha < 0.0 || alpha > 1.0) {
            throw new IllegalArgumentException("Significance out of range: " + alpha);
        }

        return StatUtils.getZForAlpha(alpha);
    }

    private boolean twocycle(Node X, Node Y, Graph graph) {
        final List<Node> adjX = graph.getAdjacentNodes(X);
        final List<Node> adjY = graph.getAdjacentNodes(Y);

        removeTier1(adjX);
        removeTier1(adjY);

        for (Node a : new ArrayList<>(adjX)) {
            if (graph.getEdges(a, X).size() == 2 || Edges.isUndirectedEdge(graph.getEdge(a, X))) {
                adjX.remove(a);
            }
        }

        for (Node a : new ArrayList<>(adjY)) {
            if (graph.getEdges(a, Y).size() == 2 || Edges.isUndirectedEdge(graph.getEdge(a, Y))) {
                adjY.remove(a);
            }
        }

        Set<Node> adjSet = new HashSet<>(adjX);
        adjSet.addAll(adjY);
        List<Node> adj = new ArrayList<>(adjSet);
        adj.remove(X);
        adj.remove(Y);

        DepthChoiceGenerator gen = new DepthChoiceGenerator(adj.size(), Math.min(depth, adj.size()));
        int[] choice;

        while ((choice = gen.next()) != null) {
            List<Node> Z = GraphUtils.asList(choice, adj);

            boolean b1 = false, b2 = false;

            try {
                E hx = new E(X, Y, Z, null).invoke();
                E hy = new E(X, Y, Z, X).invoke();

                double[] dx = hx.getR();
                double[] dy = hy.getR();

                int nx = hx.getRows().size();
                int ny = hy.getRows().size();

                // Welch's Test
                double exyy = variance(dy) / ((double) ny);
                double exyx = variance(dx) / ((double) nx);
                double t = (mean(dy) - mean(dx)) / sqrt(exyy + exyx);
                double df = ((exyy + exyx) * (exyy + exyx)) / ((exyy * exyy) / (ny - 1)) + ((exyx * exyx) / (nx - 1));

                double p = 2 * (new TDistribution(df).cumulativeProbability(-abs(t)));
                b1 = p < getTwoCycleAlpha();
            } catch (Exception e) {
                e.printStackTrace();
            }

            try {
                E hy = new E(Y, X, Z, null).invoke();
                E hx = new E(Y, X, Z, Y).invoke();

                double[] dx = hx.getR();
                double[] dy = hy.getR();

                int nx = hx.getRows().size();
                int ny = hy.getRows().size();

                // Welch's Test
                double exyy = variance(dy) / ((double) ny);
                double exyx = variance(dx) / ((double) nx);
                double t = (mean(dx) - mean(dy)) / sqrt(exyy + exyx);
                double df = ((exyy + exyx) * (exyy + exyx)) / ((exyy * exyy) / (ny - 1)) + ((exyx * exyx) / (nx - 1));

                double p = 2 * (new TDistribution(df).cumulativeProbability(-abs(t)));
                b2 = p < getTwoCycleAlpha();
            } catch (Exception e) {
                e.printStackTrace();
            }

            if (!b1 || !b2) {
                return false;
            }
        }

        return true;
    }

    public double getTwoCycleAlpha() {
        return twoCycleAlpha;
    }

    public void setTwoCycleAlpha(double twoCycleAlpha) {
        this.twoCycleAlpha = twoCycleAlpha;
        this.twoCycleCutoff = cutoff(getTwoCycleAlpha());
    }

    public double getPenaltyDiscount() {
        return penaltyDiscount;
    }

    public void setPenaltyDiscount(double penaltyDiscount) {
        this.penaltyDiscount = penaltyDiscount;
    }

    public boolean isFaithfulnessAssumed() {
        return faithfulnessAssumed;
    }

    public void setFaithfulnessAssumed(boolean faithfulnessAssumed) {
        this.faithfulnessAssumed = faithfulnessAssumed;
    }

    public boolean isSymmetricFirstStep() {
        return symmetricFirstStep;
    }

    public void setSymmetricFirstStep(boolean symmetricFirstStep) {
        this.symmetricFirstStep = symmetricFirstStep;
    }

    public int getMaxDegree() {
        return maxDegree;
    }

    public void setMaxDegree(int maxDegree) {
        this.maxDegree = maxDegree;
    }

    private class E {
        private Node x;
        private Node y;
        private List<Node> z;
        private Node condition;
        private List<Integer> rows;
        private double[] rxy_over_erxx;

        E(Node X, Node Y, List<Node> Z, Node condition) {
            x = X;
            y = Y;
            z = Z;

            if (z.contains(x) || z.contains(y)) {
                throw new IllegalArgumentException("Z should not contain X or Y.");
            }

            this.condition = condition;
        }

        public List<Integer> getRows() {
            return rows;
        }

        public double[] getR() {
            return rxy_over_erxx;
        }

        public E invoke() {

            if (condition != null) {
                final double[] _w = colData[variables.indexOf(condition)];
                rows = StatUtils.getRows(_w, 0, +1);
            } else {
                rows = new ArrayList<>();
                for (int i = 0; i < dataSet.getNumRows(); i++) rows.add(i);
            }


            int[] _rows = new int[rows.size()];
            for (int i = 0; i < rows.size(); i++) _rows[i] = rows.get(i);

            regressionDataset.setRows(_rows);

            double[] rx = regressionDataset.regress(x, z).getResiduals().toArray();
            double[] ry = regressionDataset.regress(y, z).getResiduals().toArray();

            double[] rxy = new double[rows.size()];

            for (int i = 0; i < rows.size(); i++) {
                rxy[i] = rx[i] * ry[i];
            }

            double[] rxx = new double[rows.size()];

            for (int i = 0; i < rows.size(); i++) {
                rxx[i] = rx[i] * rx[i];
            }

            rxy_over_erxx = Arrays.copyOf(rxy, rxy.length);
            double erxx = mean(rxx);

            for (int i = 0; i < rxy_over_erxx.length; i++) rxy_over_erxx[i] /= erxx;

            return this;
        }

    }

    private boolean knowledgeOrients(Node left, Node right) {
        return knowledge.isForbidden(right.getName(), left.getName()) || knowledge.isRequired(left.getName(), right.getName());
    }

    private boolean edgeForbiddenByKnowledge(Node left, Node right) {
        return knowledge.isForbidden(right.getName(), left.getName()) && knowledge.isForbidden(left.getName(), right.getName());
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }
}







