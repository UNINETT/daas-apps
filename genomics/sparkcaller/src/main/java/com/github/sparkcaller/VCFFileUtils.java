package com.github.sparkcaller;

import picard.vcf.MergeVcfs;

import java.io.File;
import java.util.List;

public class VCFFileUtils {

    /*
    Merges all the VCF files specified in the list 'vcfFiles' and returns the filename of the output file.
    */
    public static String mergeVCFFiles(List<File> vcfFiles, String outputFileName) {
        MergeVcfs mergeEngine = new MergeVcfs();
        mergeEngine.INPUT = vcfFiles;

        // Only pass the output file as an argument,
        // as it is more efficient to set the input files directly in the object.
        String outputArgs[] = {"O=" + outputFileName};

        mergeEngine.instanceMain(outputArgs);
        return outputFileName;
    }

}