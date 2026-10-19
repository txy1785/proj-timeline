package com.projtimeline.security;

public record CurrentUser(long id, String username, String name, String globalRole) {
    public boolean isAdmin() { return "ADMIN".equals(globalRole); }
    public boolean isViewer() { return "VIEWER".equals(globalRole); }
}
