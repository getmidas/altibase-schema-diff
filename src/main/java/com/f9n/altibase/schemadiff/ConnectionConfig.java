package com.f9n.altibase.schemadiff;

public record ConnectionConfig(
        String server,
        int port,
        String user,
        String password,
        String database,
        int connectTimeoutSeconds
) {
    public String jdbcUrl() {
        return "jdbc:Altibase://" + server + ":" + port + "/" + database;
    }

    public String label() {
        return server + ":" + port + "/" + database;
    }
}
