package org.broadinstitute.sting.utils.genotype.glf;

import net.sf.samtools.SAMSequenceRecord;
import net.sf.samtools.util.BinaryCodec;
import net.sf.samtools.util.BlockCompressedOutputStream;
import org.broadinstitute.sting.gatk.contexts.variantcontext.MutableGenotype;
import org.broadinstitute.sting.gatk.contexts.variantcontext.VariantContext;
import org.broadinstitute.sting.utils.GenomeLocParser;
import org.broadinstitute.sting.utils.MathUtils;
import org.broadinstitute.sting.utils.genotype.CalledGenotype;
import org.broadinstitute.sting.utils.genotype.IndelLikelihood;
import org.broadinstitute.sting.utils.genotype.LikelihoodObject;
import org.broadinstitute.sting.utils.genotype.vcf.VCFGenotypeRecord;
import org.broadinstitute.sting.utils.pileup.PileupElement;
import org.broadinstitute.sting.utils.pileup.ReadBackedPileup;

import java.io.DataOutputStream;
import java.io.File;
import java.io.OutputStream;
/*
 * Copyright (c) 2009 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

/**
 * @author aaron
 * @version 1.0
 *          <p/>
 *          This class writes GLF files. You can either specify GLFRecords, or programaticly generate
 *          single and variable length genotype calls using the provided functions.  When you've finished
 *          generating GLF records, make sure you close the file.
 */
public class GLFWriter implements GLFGenotypeWriter {
    // our output codec
    private final BinaryCodec outputBinaryCodec;

    // the glf magic number, which identifies a properly formatted GLF file
    public static final short[] glfMagic = {'G', 'L', 'F', '\3'};

    // our header text, reference sequence name (i.e. chr1), and it's length
    private String headerText = null;
    private String referenceSequenceName = null;
    private long referenceSequenceLength = 0;

    // we need to store the last record so we can calculate the offsets
    private GLFRecord mLastRecord = null;

    // the last position written
    private int lastPos = 1;

    // a field for storing the RMS of the mapping qualities in a mutable variant context
    public static final String RMS_MAPPING_QUAL = "RMS_MAPPING_QUAL";

    /**
     * The public constructor for creating a GLF object
     *
     * @param writeTo    the location to write to
     */
    public GLFWriter(File writeTo) {
        outputBinaryCodec = new BinaryCodec(new DataOutputStream(new BlockCompressedOutputStream(writeTo)));
        outputBinaryCodec.setOutputFileName(writeTo.toString());
    }

    /**
     * The public constructor for creating a GLF object
     *
     * @param writeTo    the location to write to
     */
    public GLFWriter(OutputStream writeTo) {
        outputBinaryCodec = new BinaryCodec(writeTo);
        outputBinaryCodec.setOutputFileName(writeTo.toString());
    }

    /**
     * Write out the header information for the GLF file.  The header contains
     * the magic number, the length of the header text, the text itself, the reference
     * sequence (null terminated) preceeded by it's length, and the the genomic
     * length of the reference sequence.
     *
     * @param headerText    the header text to write
     */
    public void writeHeader(String headerText) {
        this.headerText = headerText;
        for (short aGlfMagic : glfMagic) {
            outputBinaryCodec.writeUByte(aGlfMagic);
        }
        if (!(headerText.equals(""))) {
            outputBinaryCodec.writeString(headerText, true, true);
        } else {
            outputBinaryCodec.writeInt(0);
        }
    }

    /**
     * add a point genotype to the GLF writer
     *
     * @param contig     the name of the contig you're calling in
     * @param refBase    the reference base, as a char
     * @param genomicLoc the location the location on the reference contig
     * @param readDepth  the read depth at the specified postion
     * @param rmsMapQ    the root mean square of the mapping quality
     * @param lhValues   the GenotypeLikelihoods object, representing the genotype likelyhoods
     */
    public void addCall(SAMSequenceRecord contig,
                        int genomicLoc,
                        float rmsMapQ,
                        char refBase,
                        int readDepth,
                        LikelihoodObject lhValues) {
        if ( headerText == null )
            throw new IllegalStateException("The GLF Header must be written before calls can be added");

        // check if we've jumped to a new contig
        checkSequence(contig.getSequenceName(), contig.getSequenceLength());

        GLFSingleCall callGLF = new GLFSingleCall(contig.getSequenceName(),
                                                  refBase,
                                                  genomicLoc,
                                                  readDepth,
                                                  (short) rmsMapQ,
                                                  lhValues.toDoubleArray());
        lastPos = genomicLoc;
        callGLF.write(this.outputBinaryCodec,mLastRecord);
        mLastRecord = callGLF;
    }

    /**
     * Add a genotype, given a variant context
     *
     * @param vc  the variant context representing the call to add
     */
    public void addCall(VariantContext vc) {
        if ( headerText == null )
            throw new IllegalStateException("The GLF Header must be written before calls can be added");


        char ref = vc.getReference().toString().charAt(0);
        if ( vc.getNSamples() != 1 )
            throw new IllegalArgumentException("The GLF format does not support multi-sample or no-calls");

        org.broadinstitute.sting.gatk.contexts.variantcontext.Genotype genotype = vc.getGenotypes().values().iterator().next();
        if ( genotype.isNoCall() )
            throw new IllegalArgumentException("The GLF format does not support no-calls");

        ReadBackedPileup pileup;
        double[] likelihoods;
        if ( genotype instanceof CalledGenotype) {
            pileup = ((CalledGenotype)genotype).getReadBackedPileup();
            likelihoods = ((CalledGenotype)genotype).getLikelihoods();
        } else {
            pileup = (ReadBackedPileup)genotype.getAttribute(CalledGenotype.READBACKEDPILEUP_ATTRIBUTE_KEY);
            likelihoods = (double[])genotype.getAttribute(CalledGenotype.LIKELIHOODS_ATTRIBUTE_KEY);
        }

        if ( likelihoods == null )
            throw new IllegalArgumentException("The GLF format requires likelihoods");
        LikelihoodObject obj = new LikelihoodObject(likelihoods, LikelihoodObject.LIKELIHOOD_TYPE.LOG);
        obj.setLikelihoodType(LikelihoodObject.LIKELIHOOD_TYPE.NEGATIVE_LOG);  // transform! ... to negitive log likelihoods

        // calculate the RMS mapping qualities and the read depth
        double rms = 0.0;
        int readCount = 0;

        if ( pileup != null) {
            rms = calculateRMS(pileup);
            readCount = pileup.size();
        }
        // if we can't get the rms from the read pile-up (preferred), check the tags, the VC might have it
        if (genotype.hasAttribute(RMS_MAPPING_QUAL) && new Double(0.0).equals(rms))
            rms = (Double)((MutableGenotype)genotype).getAttribute(RMS_MAPPING_QUAL);

        // if we can't get the depth from the read pile-up (preferred), check the tags, the VC might have it
        if (genotype.hasAttribute(VCFGenotypeRecord.DEPTH_KEY) && 0 == readCount)
            readCount = (Integer)((MutableGenotype)genotype).getAttribute(VCFGenotypeRecord.DEPTH_KEY);

        addCall(GenomeLocParser.getContigInfo(vc.getLocation().getContig()), (int)vc.getLocation().getStart(), (float) rms, ref, readCount, obj);
    }


    /**
     * calculate the rms , given the read pileup
     *
     * @param pileup the pileup
     *
     * @return the rms of the read mapping qualities
     */
    private double calculateRMS(ReadBackedPileup pileup) {
        int[] qualities = new int[pileup.size()];
        int index = 0;
        for (PileupElement p : pileup )
            qualities[index++] = p.getMappingQual();
        return MathUtils.rms(qualities);
    }

    /**
     * add a variable length (indel, deletion, etc) to the genotype writer
     *
     * @param contig        the name of the contig you're calling in
     * @param refBase       the reference base
     * @param genomicLoc    the location on the reference contig
     * @param readDepth     the read depth at the specified postion
     * @param rmsMapQ       the root mean square of the mapping quality
     * @param firstHomZyg   the first homozygous call
     * @param secondHomZyg  the second homozygous call
     * @param hetLikelihood the negitive log likelihood of the heterozygote,  from 0 to 255
     */
    public void addVariableLengthCall(SAMSequenceRecord contig,
                                      int genomicLoc,
                                      float rmsMapQ,
                                      int readDepth,
                                      char refBase,
                                      IndelLikelihood firstHomZyg,
                                      IndelLikelihood secondHomZyg,
                                      byte hetLikelihood) {

        if ( headerText == null )
            throw new IllegalStateException("The GLF Header must be written before calls can be added");

        // check if we've jumped to a new contig
        checkSequence(contig.getSequenceName(), contig.getSequenceLength());

        // normalize the two
        GLFVariableLengthCall call = new GLFVariableLengthCall(contig.getSequenceName(),
                                                         refBase,
                                                         genomicLoc - lastPos,
                                                         readDepth,
                                                         (short) rmsMapQ,
                                                         firstHomZyg.getLikelihood(),
                                                         secondHomZyg.getLikelihood(),
                                                         hetLikelihood,
                                                         firstHomZyg.getLengthOfIndel(),
                                                         firstHomZyg.getIndelSequence(),
                                                         secondHomZyg.getLengthOfIndel(),
                                                         secondHomZyg.getIndelSequence());
        lastPos = genomicLoc;
        call.write(this.outputBinaryCodec,mLastRecord);
        mLastRecord = call;
    }

    /**
     * add a GLF record to the output file
     *
     * @param contigName   the contig name
     * @param contigLength the contig length
     * @param rec          the GLF record to write.
     */
    public void addGLFRecord(String contigName, int contigLength, GLFRecord rec) {
        if ( headerText == null )
            throw new IllegalStateException("The GLF Header must be written before records can be added");

        checkSequence(contigName, contigLength);
        rec.write(this.outputBinaryCodec,mLastRecord);
        mLastRecord = rec;
    }

    /**
     * check to see if we've jumped to a new contig
     *
     * @param sequenceName the name for the sequence
     * @param seqLength    the sequence length
     */
    private void checkSequence(String sequenceName, int seqLength) {
        if ((referenceSequenceName == null) || (!referenceSequenceName.equals(sequenceName))) {
            if (this.referenceSequenceName != null) { // don't write the record the first time
                this.writeEndRecord();
            }
            referenceSequenceName = sequenceName;
            referenceSequenceLength = seqLength;
            lastPos = 1;
            addSequence();
        }
    }


    /** add a sequence definition to the glf */
    private void addSequence() {
        if ( headerText == null )
            throw new IllegalStateException("The GLF Header must be written before sequences can be added");

        outputBinaryCodec.writeString(referenceSequenceName, true, true);
        outputBinaryCodec.writeUInt(referenceSequenceLength);
    }

    /** write end record */
    private void writeEndRecord() {
        if ( headerText == null )
            throw new IllegalStateException("The GLF Header must be written before records can be added");

        outputBinaryCodec.writeUByte((short) 0);
    }


    /**
     * close the file.  You must close the file to ensure any remaining data gets
     * written out.
     */
    public void close() {
        writeEndRecord();
        outputBinaryCodec.close();
    }
}


