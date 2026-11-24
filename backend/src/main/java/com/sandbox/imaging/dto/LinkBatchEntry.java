package com.sandbox.imaging.dto;

/** 提供方输入批次的单行（仅伪引用与候选阻断所需属性，无原始身份） */
public record LinkBatchEntry(String localRef, String studyRef, String hospital,
                             String sex, String ageBand) {
}
