/*
* Copyright 2012-2016 Broad Institute, Inc.
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
* FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
* THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/

package org.broadinstitute.gatk.engine.recalibration;

import org.broadinstitute.gatk.utils.exceptions.UserException;
import org.broadinstitute.gatk.engine.recalibration.covariates.CycleCovariate;
import org.broadinstitute.gatk.utils.sam.GATKSAMReadGroupRecord;
import org.broadinstitute.gatk.utils.sam.GATKSAMRecord;
import org.broadinstitute.gatk.utils.sam.ReadUtils;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * @author Mauricio Carneiro
 * @since 3/1/12
 */
public class CycleCovariateUnitTest {
    CycleCovariate covariate;
    RecalibrationArgumentCollection RAC;

    @BeforeClass
    public void init() {
        RAC = new RecalibrationArgumentCollection();
        covariate = new CycleCovariate();
        covariate.initialize(RAC);
    }

    @BeforeMethod
    public void initCache() {
        ReadCovariates.clearKeysCache();
    }

    @Test(enabled = true)
    public void testSimpleCycles() {
        short readLength = 10;
        GATKSAMRecord read = ReadUtils.createRandomRead(readLength);
        read.setReadPairedFlag(true);
        read.setReadGroup(new GATKSAMReadGroupRecord("MY.ID"));
        read.getReadGroup().setPlatform("illumina");

        ReadCovariates readCovariates = new ReadCovariates(read.getReadLength(), 1);
        covariate.recordValues(read, readCovariates);
        verifyCovariateArray(readCovariates.getMismatchesKeySet(), 1, (short) 1);

        read.setReadNegativeStrandFlag(true);
        covariate.recordValues(read, readCovariates);
        verifyCovariateArray(readCovariates.getMismatchesKeySet(), readLength, -1);

        read.setSecondOfPairFlag(true);
        covariate.recordValues(read, readCovariates);
        verifyCovariateArray(readCovariates.getMismatchesKeySet(), -readLength, 1);

        read.setReadNegativeStrandFlag(false);
        covariate.recordValues(read, readCovariates);
        verifyCovariateArray(readCovariates.getMismatchesKeySet(), -1, -1);
    }

    private void verifyCovariateArray(int[][] values, int init, int increment) {
        for (short i = 0; i < values.length; i++) {
            short actual = Short.decode(covariate.formatKey(values[i][0]));
            int expected = init + (increment * i);
            Assert.assertEquals(actual, expected);
        }
    }

    @Test(enabled = true, expectedExceptions={UserException.class})
    public void testMoreThanMaxCycleFails() {
        int readLength = RAC.MAXIMUM_CYCLE_VALUE + 1;
        GATKSAMRecord read = ReadUtils.createRandomRead(readLength);
        read.setReadPairedFlag(true);
        read.setReadGroup(new GATKSAMReadGroupRecord("MY.ID"));
        read.getReadGroup().setPlatform("illumina");

        ReadCovariates readCovariates = new ReadCovariates(read.getReadLength(), 1);
        covariate.recordValues(read, readCovariates);
    }

    @Test(enabled = true)
    public void testMaxCyclePasses() {
        int readLength = RAC.MAXIMUM_CYCLE_VALUE;
        GATKSAMRecord read = ReadUtils.createRandomRead(readLength);
        read.setReadPairedFlag(true);
        read.setReadGroup(new GATKSAMReadGroupRecord("MY.ID"));
        read.getReadGroup().setPlatform("illumina");

        ReadCovariates readCovariates = new ReadCovariates(read.getReadLength(), 1);
        covariate.recordValues(read, readCovariates);
    }
}
