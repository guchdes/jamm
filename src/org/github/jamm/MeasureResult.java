package org.github.jamm;

/**
 * @Author Create by jiaxiaozheng
 * @Date 2022/3/17
 */
public class MeasureResult {

    private final long byteSize;

    private final long objectCount;

    private final long measureCostMills;

    public MeasureResult(long byteSize, long objectCount, long measureCostMills) {
        this.byteSize = byteSize;
        this.objectCount = objectCount;
        this.measureCostMills = measureCostMills;
    }

    public long getByteSize() {
        return byteSize;
    }

    public long getKBSize() {
        return byteSize / 1024;
    }

    public long getMBSize() {
        return getKBSize() / 1024;
    }

    public long getObjectCount() {
        return objectCount;
    }

    public long getMeasureCostMills() {
        return measureCostMills;
    }

    @Override
    public String toString() {
        return "MeasureResult{" +
                "byteSize=" + byteSize +
                ", objectCount=" + objectCount +
                ", measureCostMills=" + measureCostMills +
                '}';
    }
}
