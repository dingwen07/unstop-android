package net.extrawdw.apps.unstop;

interface IUnstopService {
    String unstop(in String[] packageNames, in int[] targetUserIds, String trigger) = 1;
    String listUsers() = 2;
    String discoverFcmApps(int userId) = 3;
    String configureFcmConnectionProtection(boolean enabled, long pollingIntervalMillis, String trigger) = 4;
    String attachLogDirectory(String logDirectory) = 5;
    void destroy() = 16777114;
}
