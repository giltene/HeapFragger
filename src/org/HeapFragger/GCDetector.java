/**
 * GCDetector.java
 * Written by Gil Tene of Azul Systems, and released to the public domain,
 * as explained at http://creativecommons.org/publicdomain/zero/1.0/
 *
 * @author Gil Tene
 * @version 1.0.9
 */

package org.HeapFragger;

import java.io.PrintStream;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

class GCDetector {
    private static final int churningLongArrayLength = 32;
    private static final int numBytesPerChurningByteArray = 24 + (churningLongArrayLength * 8);
    private static final long churningAllocsBetweenEnqueues = 1000;
    private static final int numberOfRetainedWeakRefsInWaitForGC = 10000;

    private static final long churningAllocsBetweenRetentionsWhileWaitingForPromotion = 100;

    private Yielder yielder;
    private PrintStream log;
    private boolean verbose;

    private volatile Object tempObj;
    private volatile WeakReference<Object> tempWeakRef;
    private volatile Reference tempRef;

    /**
     * This method exists purely to make it impossible for compilers to optimize away certain
     * internal state operations. It exposes internal volatile state via an opaque and
     * meaningless boolean return value (that depends on that state).
     *
     * @return an opaque boolean value that depends on internal volatile state.
     */
    public boolean exposeState() {
        return ((tempObj != null) || (tempWeakRef != null) || (tempRef != null));
    }

    public GCDetector(Yielder yielder, PrintStream log, boolean verbose) {
        this.yielder = yielder;
        this.log = log;
        this.verbose = verbose;
    }

    // Stuff for detecting basic garbage collection happening (usually newgen):

    private Reference trackDeadObject(ReferenceQueue<Object> queue) {
        Object o = new Object();
        tempWeakRef = new WeakReference<Object>(o, queue);
        return tempWeakRef;
    }

    public void waitForGC(List<Object> retentionList, long churningAllocsBetweenRetention) {
        long count = 0;
        ReferenceQueue<Object> referenceQueue = new ReferenceQueue<Object>();
        Reference[] referenceArray = new Reference[numberOfRetainedWeakRefsInWaitForGC];
        int referenceIndex = 0;

        try {

            // Loop until a weak referenced object is collected and it's reference is queued :
            while ((tempRef = referenceQueue.poll()) == null) {
                // Periodically add a new dead object to track:
                if ((count % churningAllocsBetweenEnqueues) == 0) {
                    referenceArray[referenceIndex] = trackDeadObject(referenceQueue);
                    referenceIndex = (referenceIndex + 1) % numberOfRetainedWeakRefsInWaitForGC;
                }

                // Allocated some stuff to churn the heap:
                tempObj = new Object[churningLongArrayLength];

                // Keep some of the churning allocation stuff if indicated. Let the rest die.
                if ((retentionList != null) && (count % churningAllocsBetweenRetention) == 0)
                    retentionList.add(tempObj);

                tempObj = null;

                // Yield as needed to throttle allocation rate:
                yielder.yieldIfNeeded(numBytesPerChurningByteArray);
                count++;
            }

            Thread.sleep(100);

        } catch (InterruptedException e) {
            if (verbose) {
                log.println("waitForGC interrupted.");
            }
        }
    }

    public void waitForGC() {
        waitForGC(null, 32);
    }

    // Stuff for detecting promotion happening:

    class ValuedWeakRef extends WeakReference<Object> {
        int value;
        ValuedWeakRef(Object o, ReferenceQueue<java.lang.Object> q, int value) {
            super(o, q);
            this.value = value;
        }
    }

    public int detectPromotion() {
        int count = 0;
        ValuedWeakRef detectedDeadRef;

        Queue<Object> agingObjects = new LinkedBlockingQueue<Object>();
        Queue<Object> agingObjectReferences = new LinkedBlockingQueue<Object>();
        ReferenceQueue<Object> referenceQueue = new ReferenceQueue<Object>();

        for (int i = 0; i < 1000; i++) {
            Object o = new Object();
            agingObjects.add(o);
            agingObjectReferences.add(new ValuedWeakRef(o, referenceQueue, i));
        }

        ArrayList<Object> retentionList = new ArrayList<Object>(1000); // Retain some stuff here until promotion detected

        do {
            // Discard one aging object at a time (one per detected GC cycle). Wait for a discarded object to
            // NOT be detected after a GC cycle, indicating that it had been promoted.
            agingObjects.poll();

            waitForGC(retentionList, churningAllocsBetweenRetentionsWhileWaitingForPromotion);
            count++;

            if (verbose) {
                log.println("\n\tPromotionDetector: Detected GC cycle " + count);
            }

            // See if any discarded objects were collected:
            detectedDeadRef = (ValuedWeakRef) referenceQueue.poll();
            if (detectedDeadRef == null) {
                // Nothing was queued, even though we know we discarded an object before a GC was detected.
                // Since the discarded object was not collected when other were, it must have been promoted.
                if (verbose) {
                    log.println("\n\tPromotionDetector: Detected promotion (found nothing in the aging " +
                            "queue after " + count + " detected GC cycles).");
                }
                break;
            } else {
                // Stuff was enqueued, indicating discarded objects were collected:
                do {
                    if (verbose) {
                        log.println("\n\tPromotionDetector: agingObjectRefQueue poll = " + detectedDeadRef.value);
                    }
                } while ((detectedDeadRef = (ValuedWeakRef) referenceQueue.poll()) != null);
            }
        } while (count < 1000);

        if (verbose) {
            log.println("\n\tPromotionDetector: GC promotion detected after " + count + " cycles.");
        }

        return count;
    }

    public int waitForPromotion() {
        int count;
        // Wait for two promotion detections, instead of just one. The reasoning for this is as follows:
        // Promotion will generally occur when an object is "old enough", but it can also occur when a survivor space
        // fills up, and even relatively young surviving objects are promoted in that case, meaning that
        // detecting a promotion of one object does not mean that all objects allocated before it had been
        // allocated too. Hence this can lead to a "false positive" indication.  Waiting for two
        // promotion detections is not completely reliable either, it's simply that the chances of such a
        // false positive happening twice is much lower...
        count = detectPromotion();
        count += detectPromotion();
        return count;
    }


    public static void main(String[] args) {
        final int MB = 1024*1024;

        long allocMBsPerSec = 1000;
        long yielderMillis = 5;
        long maxYieldCreditMs = 30;
        long yieldCountBetweenReports = 20;
        boolean verbose = true;

        Yielder yielder = new Yielder(allocMBsPerSec * MB,
                yielderMillis,
                maxYieldCreditMs,
                yieldCountBetweenReports,
                verbose);

        GCDetector detector = new GCDetector(yielder, System.out, true);

        while (true) {
            System.out.println("Waiting for Promotion:");
            int count = detector.waitForPromotion();
            System.out.println("\n!!! GC promotion detected after " + count + " cycles !!!");
        }
    }
}
