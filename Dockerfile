# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先拷贝 pom 利用 Docker 层缓存预下载依赖
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B clean package -DskipTests

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# 创建非 root 用户
RUN groupadd -r laundry && useradd -r -g laundry -d /app -s /usr/sbin/nologin laundry

COPY --from=build /build/target/*.jar /app/app.jar
RUN chown -R laundry:laundry /app
USER laundry

EXPOSE 8080

# 容器健康检查（镜像自带 bash，使用 /dev/tcp 探测 actuator/health）
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=12 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && \
      printf "GET /actuator/health HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && \
      grep -q "\"status\":\"UP\"" <&3'

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
