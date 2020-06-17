package org.jitsi.videobridge.stats;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;
import org.hyperic.sigar.SigarException;
import org.hyperic.sigar.cmd.SigarCommandBase;
import org.jitsi.utils.logging.Logger;

public class OsStatistics {
    private static OsStatistics instance = null;

    private static final Logger logger = Logger.getLogger(OsStatistics.class);

    private CPUInfo cpuInfo;

    private static int convertBytesToMB(long bytes) {
        return (int)(bytes / 1000000L);
    }

    public static OsStatistics getOsStatistics() {
        if (instance == null)
            instance = new OsStatistics();
        return instance;
    }

    private Method freeMemoryMethod = null;

    private final OperatingSystemMXBean operatingSystemMXBean;

    private Integer totalMemory = null;

    private OsStatistics() {
        this.operatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();
        this.cpuInfo = new CPUInfo();
    }

    public double getCPUUsage() {
        if (this.cpuInfo == null)
            return -1.0D;
        try {
            return this.cpuInfo.getCPUUsage();
        } catch (Throwable e) {
            if (e instanceof UnsatisfiedLinkError)
                this.cpuInfo = null;
            logger.error("Failed to retrieve the cpu usage.", e);
            return -1.0D;
        }
    }

    public int getTotalMemory() {
        if (this.totalMemory == null) {
            Method method;
            try {
                method = this.operatingSystemMXBean.getClass().getMethod("getTotalPhysicalMemorySize", new Class[0]);
            } catch (Exception e) {
                logger.error("The statistics of the size of the total memory is not available.");
                return -1;
            }
            method.setAccessible(true);
            Long totalMemoryBytes = Long.valueOf(0L);
            try {
                totalMemoryBytes = (Long)method.invoke(this.operatingSystemMXBean, new Object[0]);
            } catch (Exception e) {
                logger.error("The statistics of the size of the total memory is not available.");
                return -1;
            }
            this.totalMemory = Integer.valueOf(convertBytesToMB(totalMemoryBytes.longValue()));
        }
        return this.totalMemory.intValue();
    }

    public int getUsedMemory() {
        if (this.totalMemory == null)
            return -1;
        if (this.freeMemoryMethod == null) {
            try {
                this.freeMemoryMethod = this.operatingSystemMXBean.getClass().getMethod("getFreePhysicalMemorySize", new Class[0]);
            } catch (Exception e) {
                logger.error("The statistics of the size of the used memory is not available.");
                return -1;
            }
            this.freeMemoryMethod.setAccessible(true);
        }
        int memoryInMB = -1;
        try {
            long memoryInBytes = ((Long)this.freeMemoryMethod.invoke(this.operatingSystemMXBean, new Object[0])).longValue();
            memoryInMB = this.totalMemory.intValue() - convertBytesToMB(memoryInBytes);
        } catch (Exception e) {
            logger.error("The statistics of the size of the used memory is not available.");
        }
        return memoryInMB;
    }

    private static class CPUInfo extends SigarCommandBase {
        private CPUInfo() {}

        public double getCPUUsage() throws SigarException {
            return this.sigar.getCpuPerc().getCombined();
        }

        public void output(String[] arg0) throws SigarException {}
    }
}
