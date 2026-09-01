package net.extrawdw.apps.unstop;

interface IUnstopService {
    String unstop(in String[] packageNames, in int[] targetUserIds, String trigger) = 1;
    String listUsers() = 2;
    String discoverFcmApps(int userId) = 3;
    String configureFcmConnectionProtection(boolean enabled, long pollingIntervalMillis, String trigger) = 4;
    String getServiceLogFileName() = 5;
    void attachLogPath(String logPath) = 6;
    String requestFcmReconnect(String reason) = 7;
    void destroy() = 16777114;
}
