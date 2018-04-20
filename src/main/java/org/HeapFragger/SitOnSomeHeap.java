package org.HeapFragger;

import java.lang.management.ManagementFactory;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.atomic.AtomicLong;

/**
* Created with IntelliJ IDEA.
* User: gil
* Date: 4/6/13
* Time: 8:40 AM
* To change this template use File | Settings | File Templates.
*/
class SitOnSomeHeap {
    List<RefObject>[] buckets;
    ListIterator<RefObject>[] bucketIterators;
    AtomicLong targetBucketCount = new AtomicLong(0);

    SitOnSomeHeap() {
        buckets = new List[1];
        bucketIterators = new ListIterator[1];
        buckets[0] = new LinkedList<RefObject>();;
        addObject(buckets[0], null);
        bucketIterators[0] = buckets[0].listIterator();
    }

    RefObject addObject(List<RefObject> list, Object prevObj) {
        RefObject o = new RefObject();
        list.add(o);
        return o;
    }

    RefObject getTarget() {
        if (targetBucketCount.get() > 1999999999L)
            targetBucketCount.set(0);
        int bucketIndex = (int) targetBucketCount.getAndIncrement() % buckets.length;
        if (bucketIterators[bucketIndex].hasNext()) {
            return (RefObject)bucketIterators[bucketIndex].next();
        } else {
            bucketIterators[bucketIndex] = buckets[bucketIndex].listIterator();
            return (RefObject)bucketIterators[bucketIndex].next();
        }
    }

    void clearTargetRefs() {
        for (List<RefObject> bucket: buckets) {
            for (RefObject refObj : bucket) {
                refObj.setRefA(null);
                refObj.setRefB(null);
            }
        }
    }

    // Compute the actual object footprint, in number of objects per MB
    double calculateObjectCountPerMB() {
        LinkedList<RefObject> list = new LinkedList<RefObject>();
        Object prevObj = null;
        long estimateObjCount = (512 * HeapFragger.MB / 90); // rough guess at 90 bytes

        System.gc();
        try {
                Thread.sleep(5000);
        } catch (InterruptedException e) {
                System.err.println(e.toString());
                System.err.println("(No reason why I should be woken...)");
        }

        long initialUsage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        for (long i = 0; i < estimateObjCount; i++) {
            prevObj = addObject(list, prevObj);
        }

        long bytesUsed = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() - initialUsage;

        double bytesPerObject = ((double) bytesUsed)/estimateObjCount;
        return (double)(HeapFragger.MB / bytesPerObject);
    }

    @SuppressWarnings({"unchecked"})
    public List [] sitOnSomeHeap(int heapMBtoSitOn, boolean verbose) {
        buckets = (List<RefObject>[]) new List[heapMBtoSitOn];
        bucketIterators = (ListIterator<RefObject>[]) new ListIterator[heapMBtoSitOn];
        double objCountPerMB = calculateObjectCountPerMB();

        if (verbose) {
            System.out.println("\t[SitOnSomeHeap: Calculated per-object footprint is " + HeapFragger.MB/objCountPerMB + " bytes]");
            System.out.println("\t[SitOnSomeHeap: So we'll allocate a total of " + (long)(heapMBtoSitOn * objCountPerMB) + " objects]");
        }

        for (int i = 0; i < heapMBtoSitOn; i++) {
            RefObject prevObj = null;
            // fill up a MB worth of contents in lists array slot.
            LinkedList<RefObject> bucket = new LinkedList<RefObject>();
            buckets[i] = bucket;
            for (int j = 0; j < objCountPerMB; j++) {
                prevObj = addObject(bucket, prevObj);
            }
            bucketIterators[i] = bucket.listIterator();
        }
        System.gc();
        return buckets;
    }
}
