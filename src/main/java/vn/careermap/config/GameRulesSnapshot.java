package vn.careermap.config;

/** Bản sao cấu hình gửi cho client, để đồng hồ hai vế dùng cùng con số. */
public record GameRulesSnapshot(
    long turnRollS,
    long answerTimeoutS,
    long awayAfterS,
    long heartbeatS,
    long offlineDetectS,
    long fastAutoRollS,
    long emptyRoomTtlMin,
    long finishedRoomTtlMin,
    String offlinePolicy) {}