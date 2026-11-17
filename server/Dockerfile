# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
# 先拉依赖，利用镜像层缓存
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8
COPY --from=build /build/target/runtime/it-helpdesk.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS:--Xms512m -Xmx1g} -Dfile.encoding=UTF-8 -jar /app/app.jar"]
