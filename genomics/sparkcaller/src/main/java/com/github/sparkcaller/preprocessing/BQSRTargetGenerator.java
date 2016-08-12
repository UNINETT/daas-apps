package com.github.sparkcaller.preprocessing;

import com.github.sparkcaller.BaseGATKProgram;
import com.github.sparkcaller.Utils;

import java.io.File;

/*
 * Recalibrate the base quality scores inorder to get more accurate scores.
 *
 * See:
 * https://www.broadinstitute.org/gatk/documentation/tooldocs/org_broadinstitute_gatk_tools_walkers_bqsr_BaseRecalibrator.php
 *
 * For more information.
 *
 */
public class BQSRTargetGenerator extends BaseGATKProgram {

    public BQSRTargetGenerator(String pathToReference, String knownSites, String extraArgs, String coresPerNode) {
        super("BaseRecalibrator", extraArgs);
        setReference(pathToReference);
        addArgument("-knownSites", knownSites);
        setThreads(coresPerNode);
    }

    public File generateTargets(File file) throws Exception {
        setInputFile(file.getPath());
        String outputTableFilename = Utils.removeExtenstion(file.getPath(), "bam") + "-recal_data.table";
        File outputTable = new File(outputTableFilename);

        setOutputFile(outputTable.getPath());

        try {
            executeProgram();
        } catch (org.broadinstitute.gatk.utils.exceptions.ReviewedGATKException e) {
            executeProgram();
        }
        return outputTable;
    }
}
