# 多阶段构建：JDK 8 打包，精简 JRE 8 运行。
# 构建与运行阶段都用 eclipse-temurin（glibc 基础镜像）；postgresql 驱动是纯 Java，无 JNI 依赖。

# ---------- 阶段一：构建 ----------
FROM maven:3.9-eclipse-temurin-8 AS build
WORKDIR /app
# 先只拷 pom.xml，让依赖下载层能被 Docker 缓存
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn package -DskipTests

# ---------- 阶段二：运行 ----------
FROM eclipse-temurin:8-jre
WORKDIR /app
COPY --from=build /app/target/feishu-bot-proxy-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
