package net.extrawdw.apps.unstop;

interface IUnstopService {
    int runShell(String script) = 1;
    String listUsers() = 2;
    String discoverFcmApps(int userId) = 3;
    String runShellWithOutput(String script) = 4;
    void destroy() = 16777114;
}
