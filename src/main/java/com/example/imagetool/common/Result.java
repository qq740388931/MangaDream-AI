package com.example.imagetool.common;

/**
 * 统一接口返回结果类
 * 格式：{"code":200,"msg":"success","data":{具体数据}}
 *
 * @param <T> data 泛型
 */
public class Result<T> {

    /** 状态码，200 成功，404 等为异常 */
    private Integer code;
    /** 提示信息 */
    private String msg;
    /** 业务数据，可为 null */
    private T data;

    public Result() {
    }

    public Result(Integer code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    /**
     * 成功返回，带数据
     *
     * @param data 业务数据
     * @return Result
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(200, "success", data);
    }

    /**
     * 失败/异常返回
     *
     * @param code 状态码
     * @param msg  错误信息
     * @return Result，data 为 null
     */
    public static <T> Result<T> error(Integer code, String msg) {
        return new Result<>(code, msg, null);
    }

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
