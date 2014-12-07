package org.HeapFragger;

public class RefObject {
    Object refA = null;
    Object refB = null;
    long longArray[];

    RefObject(int objArrayLength) {
        if (objArrayLength > 0)
            this.longArray = new long[objArrayLength];
    }

    RefObject() {
    }

    public long[] getLongArray() {
        return longArray;
    }

    public Object getRefA() {
        return refA;
    }

    public void setRefA(Object refA) {
        this.refA = refA;
    }

    public Object getRefB() {
        return refB;
    }

    public void setRefB(Object refB) {
        this.refB = refB;
    }
}