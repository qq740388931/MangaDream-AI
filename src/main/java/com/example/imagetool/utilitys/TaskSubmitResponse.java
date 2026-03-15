package com.example.imagetool.utilitys;


/**
 * 接口提交任务的响应实体类
 * 对应JSON结构：
 * {
 *   "code": 0,
 *   "message": "ok",
 *   "data": {
 *     "historyId": "xxx",
 *     "pollIntervalMs": 2000,
 *     "requiredCredits": 2
 *   }
 * }
 */
public class TaskSubmitResponse {
    // 响应状态码（0=成功）
    private int code;
    // 响应消息
    private String message;
    // 响应数据体
    private Data data;

    // 内部类：封装data节点的字段
    public static class Data {
        // 任务历史ID（核心字段）
        private String historyId;
        // 轮询间隔（毫秒）
        private int pollIntervalMs;
        // 所需积分
        private int requiredCredits;

        // Getter & Setter
        public String getHistoryId() {
            return historyId;
        }

        public void setHistoryId(String historyId) {
            this.historyId = historyId;
        }

        public int getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(int pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public int getRequiredCredits() {
            return requiredCredits;
        }

        public void setRequiredCredits(int requiredCredits) {
            this.requiredCredits = requiredCredits;
        }

        // 方便调试的toString
        @Override
        public String toString() {
            return "Data{" +
                    "historyId='" + historyId + '\'' +
                    ", pollIntervalMs=" + pollIntervalMs +
                    ", requiredCredits=" + requiredCredits +
                    '}';
        }
    }

    // Getter & Setter
    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Data getData() {
        return data;
    }

    public void setData(Data data) {
        this.data = data;
    }

    // 快速判断响应是否成功
    public boolean isSuccess() {
        return this.code == 0 && this.data != null && this.data.getHistoryId() != null;
    }

    // 方便调试的toString
    @Override
    public String toString() {
        return "TaskSubmitResponse{" +
                "code=" + code +
                ", message='" + message + '\'' +
                ", data=" + data +
                '}';
    }
}
