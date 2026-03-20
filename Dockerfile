FROM openjdk:8-jre-slim
WORKDIR /app

# 复制 Jar 包和脚本
COPY mangadream.jar /app/
COPY mangadream.sh /app/

# 赋予脚本权限
RUN chmod +x /app/mangadream.sh

# 暴露项目端口
EXPOSE 8080

# 启动脚本
CMD ["/app/mangadream.sh", "start"]

