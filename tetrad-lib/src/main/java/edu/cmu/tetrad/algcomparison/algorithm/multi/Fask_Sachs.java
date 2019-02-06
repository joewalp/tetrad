package edu.cmu.tetrad.algcomparison.algorithm.multi;

import edu.cmu.tetrad.algcomparison.algorithm.Algorithm;
import edu.cmu.tetrad.algcomparison.score.ScoreWrapper;
import edu.cmu.tetrad.algcomparison.utils.HasKnowledge;
import edu.cmu.tetrad.algcomparison.utils.SachsUtils;
import edu.cmu.tetrad.algcomparison.utils.UsesScoreWrapper;
import edu.cmu.tetrad.annotation.AlgType;
import edu.cmu.tetrad.annotation.Experimental;
import edu.cmu.tetrad.data.*;
import edu.cmu.tetrad.graph.EdgeListGraph;
import edu.cmu.tetrad.graph.Graph;
import edu.cmu.tetrad.util.Parameters;
import edu.pitt.dbmi.algo.resampling.GeneralResamplingTest;
import edu.pitt.dbmi.algo.resampling.ResamplingEdgeEnsemble;

import java.util.List;

/**
 * Wraps the IMaGES algorithm for continuous variables.
 * </p>
 * Requires that the parameter 'randomSelectionSize' be set to indicate how many
 * datasets should be taken at a time (randomly). This cannot given multiple values.
 *
 * @author jdramsey
 */
@edu.cmu.tetrad.annotation.Algorithm(
        name = "FASK",
        command = "fask",
        algoType = AlgType.forbid_latent_common_causes
)
@Experimental
public class Fask_Sachs implements Algorithm, HasKnowledge, UsesScoreWrapper {
    static final long serialVersionUID = 23L;
    private ScoreWrapper score;
    private IKnowledge knowledge = new Knowledge2();

    public Fask_Sachs() {

    }

    public Fask_Sachs(ScoreWrapper score) {
        this.score = score;
    }

    private Graph getGraph(edu.cmu.tetrad.search.Fask search) {
        return search.search();
    }

    @Override
    public Graph search(DataModel dataSet, Parameters parameters) {
        if (parameters.getInt("numberResampling") < 1) {
            edu.cmu.tetrad.search.Fask search = new edu.cmu.tetrad.search.Fask((DataSet) dataSet, score.getScore(dataSet, parameters));

            SachsUtils SU = new SachsUtils();
            knowledge = SU.getKnowledge(
                    parameters.getBoolean("forbidAmongInterventions",true),
                    parameters.getBoolean("requiredEdgeKnowledge", false));
            search.setKnowledge(knowledge);
            search.setDepth(parameters.getInt("depth"));
            search.setPenaltyDiscount(parameters.getDouble("penaltyDiscount"));
//            search.setSkewEdgeThreshold(parameters.getDouble("extraEdgeThreshold"));
            search.setUseFasAdjacencies(parameters.getBoolean("useFasAdjacencies"));
            search.setUseSkewAdjacencies(parameters.getBoolean("useSkewAdjacencies"));
            search.setAlpha(parameters.getDouble("twoCycleAlpha"));
            search.setAlpha(parameters.getDouble("twoCycleAlpha"));
//            search.setDelta(parameters.getDouble("faskDelta"));

//            search.setPercentBootstrapForLinearityTest(parameters.getDouble("percentBootstrapForLinearityTest"));
//            search.setNumBootstrapForLinearityTest(parameters.getInt("numBootstrapForLinearityTest"));
//            search.setCutoffForLinearityTest(parameters.getDouble("cutoffForLinearityTest"));


            return SU.pruneGraph(getGraph(search));
        } else {
            Fask_Sachs fask = new Fask_Sachs(score);

            DataSet data = (DataSet) dataSet;
            GeneralResamplingTest search = new GeneralResamplingTest(data, fask, parameters.getInt("numberResampling"));

            SachsUtils SU = new SachsUtils();
            knowledge = SU.getKnowledge(
                    parameters.getBoolean("forbidAmongInterventions",true),
                    parameters.getBoolean("requiredEdgeKnowledge", false));
            search.setKnowledge(knowledge);
            
            search.setPercentResampleSize(parameters.getInt("percentResampleSize"));
            search.setResamplingWithReplacement(parameters.getBoolean("resamplingWithReplacement"));
            
            ResamplingEdgeEnsemble edgeEnsemble = ResamplingEdgeEnsemble.Highest;
            switch (parameters.getInt("resamplingEnsemble", 1)) {
                case 0:
                    edgeEnsemble = ResamplingEdgeEnsemble.Preserved;
                    break;
                case 1:
                    edgeEnsemble = ResamplingEdgeEnsemble.Highest;
                    break;
                case 2:
                    edgeEnsemble = ResamplingEdgeEnsemble.Majority;
            }
            search.setEdgeEnsemble(edgeEnsemble);
            search.setParameters(parameters);
            search.setVerbose(parameters.getBoolean("verbose"));
            return SU.pruneGraph(search.search());
        }
    }

    @Override
    public Graph getComparisonGraph(Graph graph) {
        return new EdgeListGraph(graph);
    }

    @Override
    public String getDescription() {
        return "FASK using " + score.getDescription();
    }

    @Override
    public DataType getDataType() {
        return DataType.Continuous;
    }

    @Override
    public List<String> getParameters() {
        List<String> parameters = score.getParameters();
        parameters.add("depth");
        parameters.add("twoCycleAlpha");
        parameters.add("extraEdgeThreshold");
//        parameters.add("faskDelta");

        parameters.add("useFasAdjacencies");
        parameters.add("useSkewAdjacencies");
        
        // Resampling
        parameters.add("numberResampling");
        parameters.add("resampleSize");
        parameters.add("resamplingWithReplacement");
        parameters.add("resamplingEnsemble");
        parameters.add("verbose");
        // Sachs
        parameters.add("forbidAmongInterventions");
        parameters.add("requiredEdgeKnowledge");

        return parameters;
    }

    @Override
    public IKnowledge getKnowledge() {
        return knowledge;
    }

    @Override
    public void setKnowledge(IKnowledge knowledge) {
        this.knowledge = knowledge;
    }

    @Override
    public void setScoreWrapper(ScoreWrapper score) {
        this.score = score;
    }
}