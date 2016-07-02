package edu.cmu.tetrad.algcomparison.mixed.pag;

import edu.cmu.tetrad.algcomparison.Algorithm;
import edu.cmu.tetrad.algcomparison.Parameters;
import edu.cmu.tetrad.data.DataSet;
import edu.cmu.tetrad.graph.Graph;
import edu.cmu.tetrad.search.*;

import java.util.Map;

/**
 * @author jdramsey
 */
public class MixedGfciCG implements Algorithm {
    public Graph search(DataSet Dk, Parameters parameters) {
        ConditionalGaussianScore score = new ConditionalGaussianScore(Dk);
        GFci fgs = new GFci(score);
        return fgs.search();
    }

    @Override
    public Graph getComparisonGraph(Graph dag) {
        return new DagToPag(dag).convert();
    }

    @Override
    public String getDescription() {
        return "GFCI using a conditional Gaussian BIC score";
    }

    @Override
    public DataType getDataType() {
        return DataType.Mixed;
    }
}
