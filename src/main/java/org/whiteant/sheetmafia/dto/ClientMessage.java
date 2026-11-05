package org.whiteant.sheetmafia.dto;

public record ClientMessage(String nickname, String content, String target, Boolean agree) {
}