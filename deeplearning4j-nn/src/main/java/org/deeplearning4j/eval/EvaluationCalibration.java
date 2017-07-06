package org.deeplearning4j.eval;

import lombok.Getter;
import org.deeplearning4j.eval.curves.ReliabilityDiagram;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.impl.accum.MatchCondition;
import org.nd4j.linalg.api.ops.impl.transforms.IsMax;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.conditions.Conditions;
import org.nd4j.linalg.lossfunctions.LossUtil;
import org.nd4j.linalg.ops.transforms.Transforms;
import org.nd4j.shade.jackson.annotation.JsonProperty;

/**
 * Tools for classifier calibration analysis:
 * - Residual plot
 * - Reliability diagram
 *
 * @author Alex Black
 */
@Getter
public class EvaluationCalibration extends BaseEvaluation<EvaluationCalibration> {

    public static final int DEFAULT_RELIABILITY_DIAG_NUM_BINS = 10;
    public static final int DEFAULT_HISTOGRAM_NUM_BINS = 50;

    private final int reliabilityDiagNumBins;
    private final int histogramNumBins;
    private final boolean excludeEmptyBins;

    private INDArray rDiagBinPosCount;
    private INDArray rDiagBinTotalCount;
    private INDArray rDiagBinSumPredictions;

    private INDArray labelCountsEachClass;
    private INDArray predictionCountsEachClass;

    private INDArray residualPlotOverall;
    private INDArray residualPlotOverallEachClass;
    private INDArray residualPlotPositiveEachClass;
    private INDArray residualPlotNegativeEachClass;

    private INDArray probHistogramOverall;              //Simple histogram over all probabilities
    private INDArray probHistogramClassX;               //Histogram - for each class separately
    private INDArray probHistogramClassXAndLabelX;      //Histogram - for each class separately, only where label is same class

    public EvaluationCalibration(){
        this(DEFAULT_RELIABILITY_DIAG_NUM_BINS, DEFAULT_HISTOGRAM_NUM_BINS, true);
    }

    public EvaluationCalibration(int reliabilityDiagNumBins, int histogramNumBins){
        this(reliabilityDiagNumBins, histogramNumBins, true);
    }

    public EvaluationCalibration(@JsonProperty("reliabilityDiagNumBins") int reliabilityDiagNumBins,
                                 @JsonProperty("histogramNumBins") int histogramNumBins,
                                 @JsonProperty("excludeEmptyBins") boolean excludeEmptyBins){
        this.reliabilityDiagNumBins = reliabilityDiagNumBins;
        this.histogramNumBins = histogramNumBins;
        this.excludeEmptyBins = excludeEmptyBins;
    }

    @Override
    public void eval(INDArray labels, INDArray networkPredictions, INDArray maskArray) {

        if (labels.rank() == 3) {
            evalTimeSeries(labels, networkPredictions, maskArray);
            return;
        }

        //Stats for the reliability diagram: one reliability diagram for each class
        // For each bin, we need: (a) the number of positive cases AND total cases, (b) the average probability

        int nClasses = labels.size(1);

        if(rDiagBinPosCount == null){
            //Initialize
            rDiagBinPosCount = Nd4j.create(reliabilityDiagNumBins, nClasses);
            rDiagBinTotalCount = Nd4j.create(reliabilityDiagNumBins, nClasses);
            rDiagBinSumPredictions = Nd4j.create(reliabilityDiagNumBins, nClasses);

            labelCountsEachClass = Nd4j.create(1, nClasses);
            predictionCountsEachClass = Nd4j.create(1, nClasses);

            residualPlotOverall = Nd4j.create(1, nClasses);
            residualPlotOverallEachClass = Nd4j.create(histogramNumBins, nClasses);
            residualPlotPositiveEachClass = Nd4j.create(histogramNumBins, nClasses);
            residualPlotNegativeEachClass = Nd4j.create(histogramNumBins, nClasses);

            probHistogramOverall = Nd4j.create(1, nClasses);
            probHistogramClassX = Nd4j.create(histogramNumBins, nClasses);
            probHistogramClassXAndLabelX = Nd4j.create(histogramNumBins, nClasses);
        }


        //First: loop over classes, determine positive count and total count - for each bin
        double binSize = 1.0 / reliabilityDiagNumBins;

        INDArray p = networkPredictions;
        INDArray l = labels;

        if(maskArray != null){
            //2 options: per-output masking, or
            if(maskArray.isColumnVector()){
                //Per-example masking
                l = l.mulColumnVector(maskArray);
            } else {
                l = l.mul(maskArray);
            }
        }

        for(int j = 0; j< reliabilityDiagNumBins; j++ ){
            INDArray geqBinLower = p.gte(j*binSize);
            INDArray ltBinUpper;
            if(j == reliabilityDiagNumBins -1){
                //Handle edge case
                ltBinUpper = p.lte(1.0);
            } else {
                ltBinUpper = p.lt((j+1)*binSize);
            }

            //Calculate bit-mask over each entry - whether that entry is in the current bin or not
            INDArray currBinBitMask = geqBinLower.muli(ltBinUpper);
            if(maskArray != null){
                if(maskArray.isColumnVector()){
                    currBinBitMask.muliColumnVector(maskArray);
                } else {
                    currBinBitMask.muli(maskArray);
                }
            }

            INDArray isPosLabelForBin = l.mul(currBinBitMask);
            INDArray maskedProbs = networkPredictions.mul(currBinBitMask);

            INDArray numPredictionsCurrBin = currBinBitMask.sum(0);

            rDiagBinSumPredictions.getRow(j).addi(maskedProbs.sum(0));
            rDiagBinPosCount.getRow(j).addi(isPosLabelForBin.sum(0));
            rDiagBinTotalCount.getRow(j).addi(numPredictionsCurrBin);
        }


        //Second, we want histograms of:
        //(a) Distribution of label classes: label counts for each class
        //(b) Distribution of prediction classes: prediction counts for each class
        //(c) residual plots, for each class - (i) all instances, (ii) positive instances only, (iii) negative only
        //(d) Histograms of probabilities, for each class

        labelCountsEachClass.addi(labels.sum(0));
        //For prediction counts: do an IsMax op, but we need to take masking into account...
        INDArray isPredictedClass = Nd4j.getExecutioner().execAndReturn(new IsMax(p.dup(), 1));
        if(maskArray != null){
            LossUtil.applyMask(isPredictedClass, maskArray);
        }
        predictionCountsEachClass.addi(isPredictedClass.sum(0));



        //Residual plots: want histogram of |labels - predicted prob|

        //ND4J's histogram op: dynamically calculates the bin positions, which is not what I want here...
        INDArray labelsSubPredicted = labels.sub(networkPredictions);
        INDArray maskedProbs = networkPredictions.dup();
        Transforms.abs(labelsSubPredicted, false);

        //if masking: replace entries with < 0 to effectively remove them
        if(maskArray != null){
            //Assume per-example masking
            INDArray newMask = maskArray.mul(-10);
            labelsSubPredicted.addiColumnVector(newMask);
            maskedProbs.addiColumnVector(newMask);
        }

        INDArray notLabels = Transforms.not(labels);
        for( int j=0; j<histogramNumBins; j++ ){
            INDArray geqBinLower = labelsSubPredicted.gte(j*binSize);
            INDArray ltBinUpper;
            INDArray geqBinLowerProbs = maskedProbs.gte(j*binSize);
            INDArray ltBinUpperProbs;
            if(j == histogramNumBins -1){
                //Handle edge case
                ltBinUpper = labelsSubPredicted.lte(1.0);
                ltBinUpperProbs = maskedProbs.lte(1.0);
            } else {
                ltBinUpper = labelsSubPredicted.lt((j+1)*binSize);
                ltBinUpperProbs = maskedProbs.lt((j+1)*binSize);
            }

            INDArray currBinBitMask = geqBinLower.muli(ltBinUpper);
            INDArray currBinBitMaskProbs = geqBinLowerProbs.muli(ltBinUpperProbs);

            INDArray countsOverall = currBinBitMask.sum(0);
            residualPlotOverall.addi(countsOverall);

            //Counts for positive class only: values are in the current bin AND it's a positive label
            INDArray isPosLabelForBin = l.mul(currBinBitMask);
            INDArray isNegLabelForBin = notLabels.muli(currBinBitMask);

            residualPlotPositiveEachClass.getRow(j).addi(isPosLabelForBin.sum(0));
            residualPlotNegativeEachClass.getRow(j).addi(isNegLabelForBin.sum(0));


            INDArray countsOverallProbs = currBinBitMaskProbs.sum(0);
            probHistogramOverall.addi(countsOverallProbs);

            INDArray isPosLabelForBinProbs = l.mul(currBinBitMaskProbs);
            INDArray isNegLabelForBinProbs = notLabels.muli(currBinBitMaskProbs);
            probHistogramClassX.getRow(j).addi(isPosLabelForBinProbs.sum(0));
            probHistogramClassXAndLabelX.getRow(j).addi(isNegLabelForBinProbs.sum(0));
        }





    }

    @Override
    public void eval(INDArray labels, INDArray networkPredictions) {
        eval(labels, networkPredictions, (INDArray)null);
    }

    @Override
    public void merge(EvaluationCalibration other) {
        if(reliabilityDiagNumBins != other.reliabilityDiagNumBins){
            throw new UnsupportedOperationException("Cannot merge EvaluationCalibration instances with different numbers of bins");
        }

        if(other.rDiagBinPosCount == null){
            return;
        }

        if(rDiagBinPosCount == null){
            this.rDiagBinPosCount = other.rDiagBinPosCount;
            this.rDiagBinTotalCount = other.rDiagBinTotalCount;
            this.rDiagBinSumPredictions = other.rDiagBinSumPredictions;
        }

        this.rDiagBinPosCount.addi(other.rDiagBinPosCount);
        this.rDiagBinTotalCount.addi(other.rDiagBinTotalCount);
        this.rDiagBinSumPredictions.addi(other.rDiagBinSumPredictions);
    }

    @Override
    public void reset() {
        rDiagBinPosCount = null;
        rDiagBinTotalCount = null;
        rDiagBinSumPredictions = null;
    }

    @Override
    public String stats() {
        return "EvaluationCalibration(nBins=" + reliabilityDiagNumBins + ")";
    }

    public ReliabilityDiagram getReliabilityDiagram(int classNum){

        INDArray totalCountBins = rDiagBinTotalCount.getColumn(classNum);
        INDArray countPositiveBins = rDiagBinPosCount.getColumn(classNum);

        double[] meanPredictionBins = rDiagBinSumPredictions.getColumn(classNum)
                .div(totalCountBins).data().asDouble();

        double[] fracPositives = countPositiveBins.div(totalCountBins).data().asDouble();

        if(excludeEmptyBins){
            MatchCondition condition = new MatchCondition(totalCountBins, Conditions.equals(0));
            int numZeroBins = Nd4j.getExecutioner().exec(condition, Integer.MAX_VALUE).getInt(0);
            if(numZeroBins != 0){
                double[] mpb = meanPredictionBins;
                double[] fp = fracPositives;

                meanPredictionBins = new double[totalCountBins.length() - numZeroBins];
                fracPositives = new double[meanPredictionBins.length];
                int j=0;
                for( int i=0; i<mpb.length; i++ ){
                    if(totalCountBins.getDouble(i) != 0){
                        meanPredictionBins[j] = mpb[i];
                        fracPositives[j] = fp[i];
                        j++;
                    }
                }
            }
        }

        return new ReliabilityDiagram(meanPredictionBins, fracPositives);
    }
}
