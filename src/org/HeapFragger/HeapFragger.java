/**
 * HeapFragger.java
 * Written by Gil Tene of Azul Systems, and released to the public domain,
 * as explained at http://creativecommons.org/publicdomain/zero/1.0/
 *
 * @author Gil Tene
 * @version 1.0.9
 */

package org.HeapFragger;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

// HeapFragger: A heap fragmentation inducer, meant to induce compaction of
// the heap on a regular basis using a limited (and settable) amount of
// CPU and memory resources.
//
// The purpose of HeapFragger is [among other things] to aid application
// testers in inducing inevitable-but-rare garbage collection events,
// such that they would occur on a regular and more frequent and reliable
// basis. Doing so allows the characterization of system behavior, such
// as response time envelope, within practical test cycle times.
//
// HeapFragger works on the simple basis of repeatedly generating large sets
// of objects of a given size, pruning each set down to a much smaller
// remaining live set after it has been promoted, and increasing the object
// size between passes such that is becomes unlikely to fit in the areas freed
// up by objects released in a previous pass without some amount of compaction.
// HeapFragger ages object sets before pruning them down in order to bypass
// potential artificial early compaction by young generation collectors.
//
// By the time enough passes are done such that aggregate space allocated
// by the passes roughly matches the heap size (although a much smaller
// percentage is actually alive), some level of compaction likely or
// inevitable.
//
// HeapFragger's resource consumption is completely tunable, it will throttle
// itself to a tunable rate of allocation, and limit it's heap footprint
// to configurable level. When run with default settings, HeapFragger will
// occupy 10% of total heap space, and allocate objects at a rate
// of 50MB/sec.
//
// Altering the allocation rate and the peakMBPerIncrement parameter
// (a larger peakMBPerIncrement will require fewer churning passes in each
// compaction-inducing iteration), will change the frequency with which
// compactions occur.The main (common) settable items are:
//
// allocMBsPerSec [-a, default: 50]: Allocation rate - controls the CPU %
// HeapFragger occupies, and affects compaction event freq.
//
// heapBudgetAsFraction [-f, default: 0.1] or
// heapBudgetInMB [-b, default: derived from heapBudgetAsFraction] can
// be used to controls the peak amount of heap space that the HeapFragger
// will use for it's temporary churning storage needs. While the default
// setting is to use ~10% of the detected heap size, higher heap budgets
// can be used to allow HeapFragger to fragment the heap more quickly.
//
// heapMBtoSitOn [-s, default: 0]: Useful for experimenting with the
// effects of varying heap occupancies on compaction times. Causes
// HeapFragger to pre-allocate an additional static live set of the given
// size.
//
// pauseThresholdMs [-t, default: 350]: For convenience, HeapFragger includes
// a simple pause detector that will report on detected pauses to stderr. The
// pauseThresholdMs parameter controls the threshold below which detected
// pauses will not be reported.
//
// HeapFragger will typically be added to existing Java applications as a
// javaagent. E.g. a typical command line will be:
//
// java ... -javaagent:HeapFragger.jar="-a 100" MyApp myAppArgs
//
// The HeapFragger jar also includes a convenient Idle class that can be
// used for demo purposes. E.g. the following command line will demonstrate
// periodic promotion-failure related pauses with the HotSpot CMS collector,
// with each resulting Full GC having to deal with at least 512MB of live
// matter in the heap:
//
// java -Xmx2g -Xmx2g -XX:+UseConcMarkSweepGC -XX:+PrintGCApplicationStoppedTime
//  -XX:+PrintGCDetails -Xloggc:gc.log -javaagent:HeapFragger.jar="-a 400 -s 512"
//    org.HeapFragger.Idle -t 1000000000


public class HeapFragger extends Thread {

    static final int MB = 1024 * 1024;

    PrintStream log;

    class HeapFraggerConfiguration {
        public long allocMBsPerSec = 50;

        public double heapBudgetAsFraction = 0.1;
        public int heapBudgetInMB = -1;
        public int peakMBPerIncrement = 100;
        public int numStoreIncrements = 0; // Calculated based on estimatedHeapMB and peakMBPerIncrement

        public int pruneRatio = 53;
        public long pruneOpsPerSec = 2 * 1000 * 1000;
        public long shuffleOpsPerSec = 20 * 1000 * 1000;
        public long yielderMillis = 5;
        public long maxYieldCreditMs = 30;
        public long yieldCountBetweenReports = 20;

        public long pauseThresholdMs = 350;

        public int initialFragObjectSize = 96;
        public int estimatedArrayOverheadInBytes = 24;
        public double fragObjectSizeMultiplier = 1.0;
        public long fragObjectSizeIncrement = 32;

        public long estimatedHeapMB = 0;
        public int fragStoreBucketCount = 101;
        public boolean verbose = false;
        public int heapMBtoSitOn = 0;

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
                } else if(args[i].equals("-s")) {
                    heapMBtoSitOn = Integer.parseInt(args[++i]);
                } else if (args[i].equals("-b")) {
                    heapBudgetInMB = Integer.parseInt(args[++i]);
                } else if (args[i].equals("-f")) {
                    heapBudgetAsFraction = Double.parseDouble(args[++i]);
                } else if (args[i].equals("-t")) {
                    pauseThresholdMs = Long.parseLong(args[++i]);
                } else if (args[i].equals("-i")) {
                    fragObjectSizeIncrement = Long.parseLong(args[++i]);
                } else if (args[i].equals("-m")) {
                    fragObjectSizeMultiplier = Double.parseDouble(args[++i]);
                } else if (args[i].equals("-r")) {
                    pruneRatio = Integer.parseInt(args[++i]);
                } else if (args[i].equals("-y")) {
                    yielderMillis = Long.parseLong(args[++i]);
                } else if (args[i].equals("-e")) {
                    estimatedHeapMB = Integer.parseInt(args[++i]);
                } else if (args[i].equals("-o")) {
                    pruneOpsPerSec = Long.parseLong(args[++i]);
                } else if (args[i].equals("-l")) {
                    logFileName = args[++i];
                } else {
                    System.out.println("Usage: java HeapFragger [-v] " +
                            "[-a allocMBsPerSec] [-b heapBudgetInMB] [-f heapBudgetAsFraction] " +
                            "[-t pauseThresholdMs ] [-e estimatedHeapMB] [-s heapMBtoSitOn]");
                    System.exit(1);
                }
            }

            if (heapBudgetInMB == -1) {
                heapBudgetInMB = (int)(estimatedHeapMB * heapBudgetAsFraction);
            }
            peakMBPerIncrement = heapBudgetInMB / 2;
            numStoreIncrements = (int) (estimatedHeapMB * 1.3 / peakMBPerIncrement) + 1; // ~1.3x the pass count that would be needed
        }

        HeapFraggerConfiguration() {
            estimateHeapSize();
        }
    }

    private HeapFragger(String[] args) throws FileNotFoundException {
        this.setName("HeapFragger");
        config.parseArgs(args);
        if (config.logFileName != null) {
            log = new PrintStream(new FileOutputStream(config.logFileName), false);
        } else {
            log = System.out;
        }
        this.setDaemon(true);
    }

    HeapFraggerConfiguration config = new HeapFraggerConfiguration();

    SitOnSomeHeap sitOnHeap = new SitOnSomeHeap();
    Object heapStuffRoot;

    class PassStore {
        ArrayList<List<RefObject>> bucketList;
        AtomicLong count = new AtomicLong(0);
        AtomicLong targetBucketCount = new AtomicLong(0);
        AtomicLong targetCounts[];

        void reset() {
            bucketList = new ArrayList<List<RefObject>>(config.fragStoreBucketCount);
            targetCounts = new AtomicLong[config.fragStoreBucketCount];
            for (int i = 0; i < config.fragStoreBucketCount; i++) {
                targetCounts[i] = new AtomicLong(0);
                List<RefObject> bucket = Collections.synchronizedList(new ArrayList<RefObject>());
                bucket.add(new RefObject(0));
                bucketList.add(i, bucket);
            }
        }

        PassStore() {
            reset();
        }

        void add(RefObject o) {
            // does not need synchronization, since individual buckets are made up of synchronized lists...
            bucketList.get((int) (count.getAndIncrement() % config.fragStoreBucketCount)).add(o);
        }

        RefObject getTargetObject() {
            if (targetBucketCount.get() > 1999999999L)
                targetBucketCount.set(0);
            int bucketIndex = (int) targetBucketCount.getAndIncrement() % config.fragStoreBucketCount;
            if (targetCounts[bucketIndex].get() > 1999999999L)
                targetCounts[bucketIndex].set(0);
            List<RefObject> bucket = bucketList.get(bucketIndex);
            try {
                synchronized (bucket) {
                    int bucketTargetIndex = (int) targetCounts[bucketIndex].getAndIncrement() % bucket.size();
                    return bucket.get(bucketTargetIndex);
                }
            } catch (Exception e) {
                log.println(e);
                return(null);
            }
        }

        synchronized void prune(Yielder yielder) throws InterruptedException {
            // Prune PassStore keeping only objects that have refA filled.
            for (int b = 0; b < bucketList.size(); b++) {
                List<RefObject> survivorList = Collections.synchronizedList(new ArrayList<RefObject>());
                ListIterator<RefObject> iter = bucketList.get(b).listIterator();
                for (int i = 0; iter.hasNext(); i++) {
                    RefObject o = iter.next();
                    if (o.getRefA() != null) { // Keep objects that have refA filled.
                        survivorList.add(o);
                        // Link to surviving objects from static set:
                        for (int j = 0; j < 100; j++) {
                            sitOnHeap.getTarget().setRefA(o);
                        }
                        yielder.yieldIfNeeded(config.pruneRatio);
                    }
                }
                bucketList.set(b, survivorList);
            }
        }
    }

    class FragMaker {
        PassStore[] stores = new PassStore[config.numStoreIncrements];
        AtomicLong targetStoreCount = new AtomicLong(0);

        Yielder allocationYielder = new Yielder(config.allocMBsPerSec * MB, config.yielderMillis,
                config.maxYieldCreditMs, config.yieldCountBetweenReports, config.verbose);
        Yielder pruneYielder = new Yielder(config.pruneOpsPerSec, config.yielderMillis,
                config.maxYieldCreditMs, config.yieldCountBetweenReports, config.verbose);
        Yielder shuffleYielder = new Yielder(config.shuffleOpsPerSec, config.yielderMillis,
                config.maxYieldCreditMs, config.yieldCountBetweenReports, config.verbose);

        GCDetector gcDetector = new GCDetector(allocationYielder, log, config.verbose);

        FragMaker() {
            for (int i = 0; i < config.numStoreIncrements; i++) {
                stores[i] = new PassStore();
            }
        }

        RefObject getTargetObject() {
            if (targetStoreCount.get() > 1999999999L)
                targetStoreCount.set(0);
            return stores[(int) targetStoreCount.getAndIncrement() % config.numStoreIncrements].getTargetObject();
        }

        synchronized void shuffleAllLinks(Yielder yielder) throws InterruptedException {
            // Shuffle all links between stores, and make sure they all refer only to pruned surviving sets.
            for (int passIndex = 0; passIndex < stores.length; passIndex++) {
                PassStore passStore = stores[passIndex];
                for (int b = 0; b < passStore.bucketList.size(); b++) {
                    ListIterator<RefObject> iter = passStore.bucketList.get(b).listIterator();
                    while (iter.hasNext()) {
                        RefObject o = iter.next();
                        o.setRefA(getTargetObject());
                        o.setRefB(getTargetObject());
                        yielder.yieldIfNeeded(2);
                    }
                }
            }
        }

        // Make a bunch of small objects
        public void doPass(int passIncrementNumber, int fragObjectSize) throws InterruptedException {
            PassStore store = stores[passIncrementNumber];
            store.reset();

            // Create MaxPassHeapFractionm worth  of objects of ~fragObjSie in size in this pass store:

            long targetObjCount = ((long) (config.peakMBPerIncrement * MB)) / fragObjectSize;
            int longArrayLength = (fragObjectSize - (config.estimatedArrayOverheadInBytes * 2))/8;

            if (config.verbose) {
                log.println("\nHeapFragger: Pass Increment #" + passIncrementNumber + ": Making " +
                        targetObjCount + " Objects of size " + fragObjectSize);
            }

            for (int i = 0; i < targetObjCount; i++) {
                RefObject o = new RefObject(longArrayLength);
                RefObject target = getTargetObject();
                if ((i % config.pruneRatio) == 0) {
                    // Fill refA in objects to indicate we want them to survive a prune
                    o.setRefA(target);
                }
                o.setRefB(target);
                store.add(o);
                allocationYielder.yieldIfNeeded(fragObjectSize);
            }

            // Wait for promotion, to get the objects created in this pass into OldGen:
            if (config.verbose) {
                log.print("\nHeapFragger: Waiting for promotion: ");
            }

            gcDetector.waitForPromotion();

            if (config.verbose) {
                log.println("HeapFragger: Promotion detected.");
            }

            // Now that they are all old, prune the objects created in this pass by pruneRatio, keeping only
            // a fraction equal to 1/pruneRatio alive:

            if (config.verbose) {
                log.println("\nHeapFragger: Pruning frag pass by prune ratio " + config.pruneRatio);
            }
            store.prune(pruneYielder);

            // Shuffle target links in surviving pruned lists across all pass Stores, to keep things interesting:

            if (config.verbose) {
                log.println("\nHeapFragger: Connecting surviving links.");
            }
            shuffleAllLinks(shuffleYielder);
        }

        public void frag() throws InterruptedException {
            if (config.verbose) {
                log.println("HeapFragger: Estimated Heap " + config.estimatedHeapMB +
                        " MB, Starting fragger pass with " + config.numStoreIncrements + " increments.");
            }

            int fragObjectSize = config.initialFragObjectSize;

            for (int storeIncrementNumber = 0; storeIncrementNumber < config.numStoreIncrements; storeIncrementNumber++) {
                // Generate a fragmented set of objects of the current size in OldGen
                doPass(storeIncrementNumber, fragObjectSize);
                // Grow the object size by a fixed multiple and increment:
                fragObjectSize *= config.fragObjectSizeMultiplier;
                fragObjectSize += config.fragObjectSizeIncrement;
            }
        }
    }

    public void run() {
        if (config.heapMBtoSitOn > 0) {
            if(config.verbose) {
                log.println("HeapFragger: Creating " + config.heapMBtoSitOn + "MB of Heap to sit on...");
            }
            heapStuffRoot = sitOnHeap.sitOnSomeHeap(config.heapMBtoSitOn, config.verbose);
        }

        PauseDetector pauseDetector = new PauseDetector(config.yielderMillis, config.pauseThresholdMs);
        pauseDetector.start();

        FragMaker f = new FragMaker();
        try {
            int passNumber = 0;
            while (true) {
                if (config.verbose) {
                    log.println("\nStarting a HeapFragger pass " + (passNumber++) + " ...");
                }
                f.frag();
            }
        } catch (InterruptedException e) {
            log.println("HeapFragger: Interrupted, exiting...");
        }
        if (config.verbose) {
            log.println("\nHeapFragger Done...");
        }
    }

    public static HeapFragger commonMain(String[] args) {
        HeapFragger heapFragger = null;
        try {
            heapFragger = new HeapFragger(args);

            if (heapFragger.config.verbose) {
                heapFragger.log.print("Executing: HeapFragger");
                for (String arg : args) {
                    heapFragger.log.print(" " + arg);
                }
                heapFragger.log.println("");
            }
            heapFragger.start();
        } catch (FileNotFoundException e) {
            System.err.println("HeapFragger: Failed to open log file.");
        }
        return heapFragger;
    }

    public static void premain(String argsString, java.lang.instrument.Instrumentation inst) {
        String[] args = (argsString != null) ? argsString.split("[ ,;]+") : new String[0];
        commonMain(args);
    }

    public static void main(String[] args) {
        final HeapFragger heapFragger = commonMain(args);

        if (heapFragger != null) {
            // The HeapFragger thread, on it's own, will not keep the JVM from exiting. If nothing else
            // is running (i.e. we we are the main class), then keep main thread from exiting
            // until the HiccupMeter thread does...
            try {
                heapFragger.join();
            } catch (InterruptedException e) {
                if (heapFragger.config.verbose) {
                    heapFragger.log.println("HiccupMeter main() interrupted");
                }
            }
        }
    }
}
