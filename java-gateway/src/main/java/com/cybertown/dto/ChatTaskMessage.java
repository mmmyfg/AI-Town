package com.cybertown.dto;

public class ChatTaskMessage {
    private String mode = "single";
    private java.util.List<SceneParticipant> participants;
    private int sceneRounds = 1;
    private String taskId;
    private String traceId;
    private String userMessage;
    private String playerId;
    private String npcId;
    private String conversationId;
    private String personality;
    private String observation;
    private String memory;
    private String mood;
    private int currentAffinity;

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public java.util.List<SceneParticipant> getParticipants() { return participants; }
    public void setParticipants(java.util.List<SceneParticipant> participants) { this.participants = participants; }
    public int getSceneRounds() { return sceneRounds; }
    public void setSceneRounds(int sceneRounds) { this.sceneRounds = sceneRounds; }

    public static class SceneParticipant {
        private String npcId;
        private String personality;
        private String observation;
        private String memory;
        private String mood;
        private int currentAffinity;
        public String getNpcId() { return npcId; }
        public void setNpcId(String npcId) { this.npcId = npcId; }
        public String getPersonality() { return personality; }
        public void setPersonality(String personality) { this.personality = personality; }
        public String getObservation() { return observation; }
        public void setObservation(String observation) { this.observation = observation; }
        public String getMemory() { return memory; }
        public void setMemory(String memory) { this.memory = memory; }
        public String getMood() { return mood; }
        public void setMood(String mood) { this.mood = mood; }
        public int getCurrentAffinity() { return currentAffinity; }
        public void setCurrentAffinity(int currentAffinity) { this.currentAffinity = currentAffinity; }
    }

    public String getTaskId() {
        return taskId;
    }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getUserMessage() { return userMessage; }
    public void setUserMessage(String userMessage) { this.userMessage = userMessage; }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getPlayerId() {
        return playerId;
    }

    public void setPlayerId(String playerId) {
        this.playerId = playerId;
    }

    public String getNpcId() {
        return npcId;
    }

    public void setNpcId(String npcId) {
        this.npcId = npcId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getPersonality() {
        return personality;
    }

    public void setPersonality(String personality) {
        this.personality = personality;
    }

    public String getObservation() {
        return observation;
    }

    public void setObservation(String observation) {
        this.observation = observation;
    }

    public String getMemory() {
        return memory;
    }

    public void setMemory(String memory) {
        this.memory = memory;
    }

    public String getMood() {
        return mood;
    }

    public void setMood(String mood) {
        this.mood = mood;
    }

    public int getCurrentAffinity() { return currentAffinity; }
    public void setCurrentAffinity(int currentAffinity) { this.currentAffinity = currentAffinity; }
}
