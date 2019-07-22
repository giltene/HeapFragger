/**
 * heaputils.java
 * Written by Gil Tene of Azul Systems, and released to the public domain,
 * as explained at http://creativecommons.org/publicdomain/zero/1.0/
 *
 * @author Gil Tene
 * @version 1.0.9
 */

package org.heaputils;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.*;

// CardScanThrasher: A card scanning "hard work" inducer. Intended to see
// how bad card scanning (of old to new roots) can end up costing a newgen
// collection. CardScanThrasher is NOT intended to demonstrate common case
// behaviors or suggest that newgen collections are not a good idea. It is
// meant to measure the edge of "how bad can things get?"


public class CardScanThrasher extends Thread {

    static final int MB = 1024 * 1024;

    PrintStream log;

    class CardScanThrasherConfiguration {
        public long allocMBsPerSec = 1000;

        public long oldgenFootprint = 1024L * 1024L * 1024L; // 1GB
        public long newgenFootprint = 1024L * 1024L * 80L; // 80MB

        public int lengthOfOldgenRefArray = 128; // Good for at least one card with 4 byte refs
        public long estimatedSizeOfOldgenRefArray = 24 + (8 * lengthOfOldgenRefArray);

        public int estimatedSizeOfNewGenObject = 24;
        public int numNewgenObjects = 0;

        public long yielderMillis = 5;
        public long maxYieldCreditMs = 30;
        public long yieldCountBetweenReports = 20;

        public long pauseThresholdMs = 100;

        public long estimatedHeapMB = 0;
        public boolean verbose = false;

        public String logFileName = null;

        void estimateHeapSize() {
            MemoryMXBean mxbean = ManagementFactory.getMemoryMXBean();
            MemoryUsage memoryUsage = mxbean.getHeapMemoryUsage();
            estimatedHeapMB = (int) (memoryUsage.getMax() / (1024 * 1024));
        }

        public void parseArgs(String[] args) {
            estimateHeapSize();
            for (int i = 0; i < args.length; ++i) {
                if (args[i].equals("-v")) {
                    verbose = true;
                } else if (args[i].equals("-a")) {
                    allocMBsPerSec = Long.parseLong(args[++i]);
                } else if (args[i].equals("-t")) {
                    pauseThresholdMs = Long.parseLong(args[++i]);
                } else if (args[i].equals("-o")) {
                    oldgenFootprint = Long.parseLong(args[++i]) * 1024L * 1024L;
                } else if (args[i].equals("-n")) {
                    newgenFootprint = Long.parseLong(args[++i]) * 1024L * 1024L;
                } else if (args[i].equals("-y")) {
                    yielderMillis = Long.parseLong(args[++i]);
                } else if (args[i].equals("-e")) {
                    estimatedHeapMB = Integer.parseInt(args[++i]);
                } else if (args[i].equals("-l")) {
                    logFileName = args[++i];
                } else {
                    System.out.println("Usage: java heaputils [-v] " +
                            "[-a allocMBsPerSec] [-o oldgenFootprintInMB] [-n newgenFootprintInMB] " +
                            "[-t pauseThresholdMs ]");
                    System.exit(1);
                }
            }

            if (numNewgenObjects == 0) {
                long numObjectsNeeded = newgenFootprint / estimatedSizeOfNewGenObject;
                if (numObjectsNeeded > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("Args require more than 2B newgen objects... Pick smaller numbers");
                }
                numNewgenObjects = (int) numObjectsNeeded;
            }
        }

        CardScanThrasherConfiguration() {
            estimateHeapSize();
        }
    }

    private CardScanThrasher(String[] args) throws FileNotFoundException {
        this.setName("CardScanThrasher");
        config.parseArgs(args);
        if (config.logFileName != null) {
            log = new PrintStream(new FileOutputStream(config.logFileName), false);
        } else {
            log = System.out;
        }
        this.setDaemon(true);
    }

    CardScanThrasherConfiguration config = new CardScanThrasherConfiguration();

    class CardFiller {
        List<Object[]> oldGenStore = new ArrayList<Object[]>();
        Long[] newgenObjects;

        private static final int shufflingSeed = 37;
        int[] linearNextIndexes;
        int[] shuffledNextIndexes;


        Yielder allocationYielder = new Yielder(config.allocMBsPerSec * MB, config.yielderMillis,
                config.maxYieldCreditMs, config.yieldCountBetweenReports, config.verbose);

        GCDetector gcDetector = new GCDetector(allocationYielder, log, config.verbose);

        CardFiller() {
            createOldgenRefStorage();
        }

        // Create oldgen places to put newgen refs into:
        void createOldgenRefStorage() {
            long estimatedOldgenFootprint = 0;
            // create lots of oldgen obj arrays of ~128 entries each (so 512 bytes of body with 4 byte
            // refs, and 1024 byte body with 8 byte refs, good for covering at least one full card in
            // any case.
            while (estimatedOldgenFootprint < config.oldgenFootprint) {
                oldGenStore.add(new Object[config.lengthOfOldgenRefArray]);
                estimatedOldgenFootprint += config.estimatedSizeOfOldgenRefArray;
            }

            // make sure card covering refs are promoted:
            gcDetector.waitForPromotion();

            newgenObjects = new Long[config.numNewgenObjects];
            initNextIndexes();
        }


        private void initNextIndexes() {
            int length = (int) config.numNewgenObjects;
            linearNextIndexes = new int[length];
            for (int i = 0; i < length - 1; i++) {
                linearNextIndexes[i] = i + 1;
            }
            linearNextIndexes[length - 1] = 0;

            shuffledNextIndexes = new int[length];
            int[] visitOrder = new int[length];
            Random generator = new Random(shufflingSeed);
            for (int i = 0; i < length; i++) {
                visitOrder[i] = i;
            }
            for (int i = length - 1; i > 0; i--) {
                int j = generator.nextInt(i + 1);
                int temp = visitOrder[i];
                visitOrder[i] = visitOrder[j];
                visitOrder[j] = temp;
            }
            for (int i = 0; i < length - 1; i++) {
                shuffledNextIndexes[visitOrder[i]] = visitOrder[i + 1];
            }
            shuffledNextIndexes[visitOrder[length - 1]] = visitOrder[0];
        }


        void createFreshBatchOfNewgenObjects() {
            long newgenObjectsCreated = 0;
            long numRefArraysInCreated = 0;
            gcDetector.waitForGC();
            // Create newgen objects and track them:
            for (int i = 0; i < config.numNewgenObjects; i++) {
                newgenObjects[i] = new Long(i);
            }

            // Have the RefArrays in the oldgen refer to newgen objects in shuffled order:
            Iterator<Object[]> oldgenStoreIter = oldGenStore.iterator();
            while (oldgenStoreIter.hasNext()) {
                Object[] refArray = oldgenStoreIter.next();
                for (int i = 0; i < refArray.length; i++) {
                    refArray[i] = newgenObjects[shuffledNextIndexes[i]];
                }
            }
        }

        public void fillCards() throws InterruptedException {
            log.println("\n******** Creating a fresh batch of Newgen objects:");
            createFreshBatchOfNewgenObjects();
            log.println("******** Waiting for a promotion:");
            gcDetector.waitForPromotion();
            log.println("******** Promotion detected, so those newgen objects aren't new any more...");
        }
    }

    public void run() {
        PauseDetector pauseDetector = new PauseDetector(config.yielderMillis, config.pauseThresholdMs);
        pauseDetector.start();

        CardFiller f = new CardFiller();
        try {
            int passNumber = 0;
            while (true) {
                if (config.verbose) {
                    log.println("\nStarting a CardScanThrasher pass " + (passNumber++) + " ...");
                }
                f.fillCards();
            }
        } catch (InterruptedException e) {
            log.println("CardScanThrasher: Interrupted, exiting...");
        }
        if (config.verbose) {
            log.println("\nCardScanThrasher Done...");
        }
    }

    public static CardScanThrasher commonMain(String[] args) {
        CardScanThrasher cardScanThrasher = null;
        try {
            cardScanThrasher = new CardScanThrasher(args);

            if (cardScanThrasher.config.verbose) {
                cardScanThrasher.log.print("Executing: CardScanThrasher");
                for (String arg : args) {
                    cardScanThrasher.log.print(" " + arg);
                }
                cardScanThrasher.log.println("");
            }
            cardScanThrasher.start();
        } catch (FileNotFoundException e) {
            System.err.println("CardScanThrasher: Failed to open log file.");
        }
        return cardScanThrasher;
    }

    public static void premain(String argsString, java.lang.instrument.Instrumentation inst) {
        String[] args = (argsString != null) ? argsString.split("[ ,;]+") : new String[0];
        commonMain(args);
    }

    public static void main(String[] args) {
        final CardScanThrasher heapFragger = commonMain(args);

        if (heapFragger != null) {
            // The heaputils thread, on it's own, will not keep the JVM from exiting. If nothing else
            // is running (i.e. we we are the main class), then keep main thread from exiting
            // until the HiccupMeter thread does...
            try {
                heapFragger.join();
            } catch (InterruptedException e) {
                if (heapFragger.config.verbose) {
                    heapFragger.log.println("CardScanThrasher main() interrupted");
                }
            }
        }
    }
}
