package git.comelf.common.metrics;

public interface MetricsCollector {

    /**
     * Add a metrics record
     * @param name  of the record
     * @return  a metrics record builder for the record
     */
    public MetricsRecordBuilder addRecord(String name);

    /**
     * Add a metrics record
     * @param info  of the record
     * @return  a metrics record builder for the record
     */
    public MetricsRecordBuilder addRecord(MetricsInfo info);

}
