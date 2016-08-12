package com.github.sparkcaller;

import com.github.sparkcaller.preprocessing.*;
import com.github.sparkcaller.variantdiscovery.GenotypeGVCF;
import com.github.sparkcaller.variantdiscovery.HaplotypeCaller;
import com.github.sparkcaller.variantdiscovery.VQSRRecalibrationApplier;
import com.github.sparkcaller.variantdiscovery.VQSRTargetCreator;
import org.apache.commons.cli.*;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.log4j.Logger;

import scala.Tuple2;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class SparkCaller {
    final private JavaSparkContext sparkContext;
    final private Logger log;
    private String pathToReference;
    private String knownSites;
    private Properties toolsExtraArgs;
    private String coresPerNode;
    private String outputFolder;

    /*
     * The SparkCaller is used for managing the workflow
     * @param pathToReference   the path to the file to use as a reference.
     *                         Keep in mind that this file has to be reachable by all nodes, and has to be indexed.
     *
     * @param pathToReference   the path to the file containing the dbsnp to use when ex. performing BQSR.
     *                         Keep in mind that this file has to be reachable by all nodes.
     *
     * @param toolsExtraArguments   the Properties object containing strings of extra arguments to pass to each tool.
     *
     */
    public SparkCaller(JavaSparkContext sparkContext, String pathToReference, String knownSites,
                       Properties toolsExtraArguments, String coresPerNode, String outputFolder) {

        this.sparkContext = sparkContext;
        this.log = Logger.getLogger(this.getClass());

        this.pathToReference = pathToReference;
        this.toolsExtraArgs = toolsExtraArguments;
        this.knownSites = knownSites;
        this.coresPerNode = coresPerNode;
        this.outputFolder = outputFolder;
    }

    public JavaRDD<File> convertToSortedBam(JavaRDD<File> files) {
        this.log.info("Converting the SAM files to sorted BAM files...");
        return files.map(new SamToSortedBam());
    }

    public File markDuplicates(File bamFile) throws Exception {
        this.log.info("Marking duplicates...");
        DuplicateMarker duplicateMarker = new DuplicateMarker(this.toolsExtraArgs.getProperty("MarkDuplicates"));
        return duplicateMarker.markDuplicates(bamFile);
    }

    /* Performs the preprocessing stage of the GATK pipeline.
     * This is performed in a simple scatter-gather manner.
     * See the following link for details: https://www.broadinstitute.org/gatk/guide/bp_step.php?p=1
     *
     * @param pathToSAMFiles   the path to the folder containing the SAM files created by the aligner.
     *
    */
    public JavaPairRDD<String, File> preprocessSAMFiles(String pathToSAMFiles) throws Exception {
        this.log.info("Preprocessing SAM files!");
        // Get all the SAM files generated by the aligner
        ArrayList<File> samFiles = Utils.getFilesInFolder(pathToSAMFiles, "sam");

        if (samFiles != null) {
            this.log.info("Distributing the SAM files to the nodes...");
            JavaRDD<File> samFilesRDD = this.sparkContext.parallelize(samFiles);

            JavaRDD<File> bamFilesRDD = this.convertToSortedBam(samFilesRDD);
            bamFilesRDD = bamFilesRDD.map(new FileMover(this.outputFolder));
            List<File> bamFiles = bamFilesRDD.collect();

            File mergedBAMFile;
            if (bamFiles.size() > 1) {
                mergedBAMFile = SAMFileUtils.mergeBAMFiles(bamFiles, "merged.bam");
            } else {
                mergedBAMFile = bamFiles.get(0);
            }

            File dedupedBAMFile = markDuplicates(mergedBAMFile);
            dedupedBAMFile = Utils.moveToDir(dedupedBAMFile, this.outputFolder);

            JavaPairRDD<String, File> realignedBamFilesRDD = realignIndels(dedupedBAMFile);
            List<File> realignedBAMFiles = realignedBamFilesRDD.values().collect();
            JavaPairRDD<String, File> recalibratedBamFilesRDD = performBQSR(realignedBAMFiles);

            this.log.info("Preprocessing finished!");
            return recalibratedBamFilesRDD;
        }

        return null;

    }

    public JavaPairRDD<String, File> performBQSR(List<File> bamFiles) throws Exception {
        this.log.info("Creating targets on which to perform BQSR...");
        File BAMFilesMerged = SAMFileUtils.mergeBAMFiles(bamFiles, "merged.bam");

        BamIndexer.indexBam(BAMFilesMerged);
        BQSRTargetGenerator bqsrTargetGenerator= new BQSRTargetGenerator(this.pathToReference,
                                                                         this.knownSites,
                                                                         this.toolsExtraArgs.getProperty("BaseRecalibrator"),
                                                                         this.coresPerNode);
        File bqsrTargets = bqsrTargetGenerator.generateTargets(BAMFilesMerged);
        bqsrTargets = Utils.moveToDir(bqsrTargets, this.outputFolder);

        List<Tuple2<String, File>> bamFilesByChromosomeTuple = SAMFileUtils.splitBAMByChromosome(BAMFilesMerged);
        List<Tuple2<String, File>> movedBAMFilesByChromosome = new ArrayList<Tuple2<String, File>>();
        for (Tuple2<String, File> bamTuple : bamFilesByChromosomeTuple) {
            File newFile = Utils.moveToDir(bamTuple._2, this.outputFolder);
            movedBAMFilesByChromosome.add(new Tuple2<>(bamTuple._1, newFile));
        }

        JavaPairRDD<String, File> bamFilesRDD = this.sparkContext.parallelizePairs(movedBAMFilesByChromosome);
        bamFilesRDD.mapValues(new BamIndexer()).collect();

        this.log.info("Performing BQSR...");
        return bamFilesRDD.mapValues(new BQSR(this.pathToReference,
                                        bqsrTargets.getPath(),
                                        this.toolsExtraArgs.getProperty("PrintReads"),
                                        this.coresPerNode));
    }

    public JavaPairRDD<String, File> realignIndels(File bamFile) throws Exception {
        this.log.info("Creating indel targets...");
        BamIndexer.indexBam(bamFile);
        IndelTargetCreator indelTargetCreator = new IndelTargetCreator(this.pathToReference,
                                                                     this.toolsExtraArgs.getProperty("RealignerTargetCreator"),
                                                                     this.coresPerNode);
        File indelTargets = indelTargetCreator.createTargets(bamFile);
        indelTargets = Utils.moveToDir(indelTargets, this.outputFolder);

        this.log.info("Splitting BAMs by chromosome...");
        List<Tuple2<String, File>> bamsByContigWithName = SAMFileUtils.splitBAMByChromosome(bamFile);
        List<Tuple2<String, File>> movedBamsByContigWithName = new ArrayList<Tuple2<String, File>>();
        for (Tuple2<String, File> bamTuple : bamsByContigWithName) {
            if (!bamTuple._1.equals("unmapped")) {
                File newFile = Utils.moveToDir(bamTuple._2, this.outputFolder);
                movedBamsByContigWithName.add(new Tuple2<>(bamTuple._1, newFile));
            }
        }
        JavaPairRDD<String, File> bamsByContigRDD = this.sparkContext.parallelizePairs(movedBamsByContigWithName);
        bamsByContigRDD.mapValues(new BamIndexer()).collect();

        this.log.info("Realigning indels...");
        JavaPairRDD<String, File> realignedIndels = bamsByContigRDD.mapValues(new RealignIndels(this.pathToReference, indelTargets,
                                                                                       this.toolsExtraArgs.getProperty("IndelRealigner")));

        return realignedIndels;
    }

    /* Performs the variant discovery stage of the GATK pipeline.
     * See the following link for details: https://www.broadinstitute.org/gatk/guide/bp_step.php?p=2
     *
     * @param preprocessedBAMFiles   a spark RDD containing the File object for each preprocessed BAM file.
     *
    */
    public File discoverVariants(JavaPairRDD<String, File> preprocessedBAMFiles) {
        this.log.info("Starting variant discovery!");
        this.log.info("Running HaplotypeCaller...");
        JavaRDD<File> variantsVCFFiles = preprocessedBAMFiles.map(new HaplotypeCaller(this.pathToReference,
                                                                  this.toolsExtraArgs.getProperty("HaplotypeCaller"),
                                                                  this.coresPerNode));
        List<File> variantsFiles = variantsVCFFiles.collect();
        this.log.info("Performing joint genotyping...");
        GenotypeGVCF genotypeGVCF = new GenotypeGVCF(this.pathToReference,
                                                     this.toolsExtraArgs.getProperty("GenotypeGVCFs"),
                                                     this.coresPerNode);
        try {
            File outputFile = new File(this.outputFolder, "merged.vcf");
            File mergedVariants = genotypeGVCF.performJointGenotyping(variantsFiles, outputFile.getPath());

            this.log.info("Recalibrating variants...");
            File recalibratedVariants = recalibrateVariants(mergedVariants);

            return recalibratedVariants;
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
            return null;
        }
    }

    private Tuple2<File, File> performVariantTargetCreation(File vcfToRecalibrate, String extraArgs, String mode) throws Exception {
        VQSRTargetCreator targetCreator = new VQSRTargetCreator(this.pathToReference, extraArgs, this.coresPerNode);
        return targetCreator.createTargets(vcfToRecalibrate, mode);

    }

    private File recalibrateVariants(File vcfToRecalibrate) {
        String INDELextraArgs = this.toolsExtraArgs.getProperty("INDELVariantRecalibrator");
        String SNPextraArgs = this.toolsExtraArgs.getProperty("SNPVariantRecalibrator");

        try {
            VQSRRecalibrationApplier vqsrApplier = new VQSRRecalibrationApplier(this.pathToReference,
                    this.toolsExtraArgs.getProperty("ApplyRecalibration"),
                    this.coresPerNode);

            if (SNPextraArgs != null) {
                Tuple2<File, File> snpTargets = performVariantTargetCreation(vcfToRecalibrate, SNPextraArgs, "SNP");
                vcfToRecalibrate = vqsrApplier.applyRecalibration(vcfToRecalibrate, snpTargets, "SNP");
            }

            if (INDELextraArgs != null) {
                Tuple2<File, File> indelTargets = performVariantTargetCreation(vcfToRecalibrate, INDELextraArgs, "INDEL");
                vcfToRecalibrate = vqsrApplier.applyRecalibration(vcfToRecalibrate, indelTargets, "INDEL");
            }

            return vcfToRecalibrate;
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);

            return null;
        }
    }

    /*
     * Handles the initialization of the pipeline, as well as running the actual pipeline in the correct order.
     *
     * @param pathToSAMFiles   the path to the folder containing the SAM files created by the aligner.
     *
     */
    public File runPipeline(String pathToSAMFiles) {

        try {
            JavaRDD<File> preprocessedBAMFiles = preprocessSAMFiles(pathToSAMFiles);
            preprocessedBAMFiles.collect();
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (preprocessedBAMFiles != null) {
            File vcfVariants = discoverVariants(preprocessedBAMFiles);
            Utils.moveToDir(vcfVariants, this.outputFolder);
            return vcfVariants;
        } else {
            System.err.println("Could not preprocess SAM files!");
            return null;
        }
    }

    public static Options initCommandLineOptions() {
        Options options = new Options();

        Option reference = new Option("R", "Reference", true, "The path to the reference file.");
        reference.setRequired(true);
        options.addOption(reference);

        Option inputFolder = new Option("I", "InputFolder", true, "The path to the folder containing the input files.");
        inputFolder.setRequired(true);
        options.addOption(inputFolder);

        Option outputFolder = new Option("O", "OutputFolder", true, "The path to the folder which will store the final output files.");
        outputFolder.setRequired(true);
        options.addOption(outputFolder);

        Option knownSites = new Option("S", "KnownSites", true, "The path to the file containing known sites (used in BQSR).");
        knownSites.setRequired(true);
        options.addOption(knownSites);

        Option configFile = new Option("C", "ConfigFile", true, "The path to the file configuration file.");
        configFile.setRequired(true);
        options.addOption(configFile);

        Option threads = new Option("CPN", "CoresPerNode", true, "The number of available cores per node.");
        threads.setRequired(true);
        options.addOption(threads);

        return options;
    }

    public static CommandLine parseCommandLineOptions(Options options, String[] argv) {
        CommandLineParser parser = new GnuParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd;

        try {
            cmd = parser.parse(options, argv);
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            formatter.printHelp("Sparkcaller", "", options,
                                "See https://github.com/UNINETT/daas-apps/tree/master/genomics for documentation.",
                                true);

            System.exit(1);
            return null;
        }

        return cmd;
    }

    public static void main(String argv[]) throws Exception {
        Options options = SparkCaller.initCommandLineOptions();
        CommandLine cmdArgs = SparkCaller.parseCommandLineOptions(options, argv);

        JavaSparkContext sparkContext = SparkCaller.initSpark("SparkCaller");

        String pathToReference = cmdArgs.getOptionValue("Reference");
        String pathToSAMFiles = cmdArgs.getOptionValue("InputFolder");
        String outputDirectory = cmdArgs.getOptionValue("OutputFolder");
        String knownSites = cmdArgs.getOptionValue("KnownSites");
        String configFilepath = cmdArgs.getOptionValue("ConfigFile");
        String coresPerNode = cmdArgs.getOptionValue("CoresPerNode");
        Properties toolsExtraArguments = Utils.loadConfigFile(configFilepath);

        SparkCaller caller = new SparkCaller(sparkContext, pathToReference, knownSites,
                                             toolsExtraArguments, coresPerNode, outputDirectory);
        caller.runPipeline(pathToSAMFiles);
    }

    public static JavaSparkContext initSpark(String appName) {
        SparkConf conf = new SparkConf().setAppName(appName);
        JavaSparkContext sparkContext = new JavaSparkContext(conf);

        return sparkContext;
    }
}
