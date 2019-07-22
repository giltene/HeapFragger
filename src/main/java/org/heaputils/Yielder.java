package org.heaputils;

/**
 * Created with IntelliJ IDEA.
 * User: gil
 * Date: 6/17/12
 * Time: 9:24 AM
 * To change this template use File | Settings | File Templates.
 */
public class Yielder {
    private long workPerSec;
    private long yieldMillis;
    private long maxCreditMs;
    private long yieldsBetweenReports;
    private long workCredit = 0;
    private long yieldCount = 0;
    private long lastYieldTimeMs;
    private boolean verbose;

    public Yielder(long workPerSec, long yieldMillis, long maxCreditMs, long yieldsBetweenReports, boolean verbose) {
        this.workPerSec = workPerSec;
        this.yieldMillis = yieldMillis;
        this.maxCreditMs = maxCreditMs;
        this.yieldsBetweenReports = yieldsBetweenReports;
        this.lastYieldTimeMs = System.currentTimeMillis();
        this.verbose = verbose;
    }

    public void yieldIfNeeded(long workDone) throws InterruptedException {
        workCredit -= workDone;
        while (workCredit <= 0) {
            Thread.sleep(yieldMillis);

            //Figure out work credit accumulated in yield:
            long currentTimeMs = System.currentTimeMillis();
            long timeCreditMs = currentTimeMs - lastYieldTimeMs;
            lastYieldTimeMs = currentTimeMs;
            if (timeCreditMs > maxCreditMs) timeCreditMs = maxCreditMs; // Cap accumulated credit;
            workCredit += (timeCreditMs * workPerSec) / 1000;

            if ((yieldCount++ % yieldsBetweenReports) == 0) {
                if (verbose) System.out.print(".");
            }
        }
    }
}
