package com.projtimeline.workspace;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record WorkspaceDeliveryPage(List<Map<String,Object>> list, long total, int page, int size,
                                    LocalDate businessDate, String timeZone, Map<String,Long> counts) {}
