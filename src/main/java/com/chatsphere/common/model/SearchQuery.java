package com.chatsphere.common.model;
/**
 * keyword: required, non-blank, case-insensitive substring. sender: "" = anyone.
 * fromTs/toTs: epoch ms inclusive, 0 = unbounded. currentOnly=true restricts to (targetType, target).
 */
public record SearchQuery(String keyword, String sender, long fromTs, long toTs,
                          boolean currentOnly, TargetType targetType, String target) {}
