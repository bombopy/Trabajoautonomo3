# syntax=docker/dockerfile:1
FROM gradle:9.6.0-jdk21-corretto AS builder
WORKDIR /build
RUN chown gradle:gradle /build

COPY --chown=gradle:gradle . ./

RUN gradle :events-consumer:installDist -x test --no-daemon

FROM amazoncorretto:21-alpine-jdk
WORKDIR /app
COPY --from=builder /build/events-consumer/build/install/events-consumer/ ./
ENTRYPOINT ["/app/bin/events-consumer"]
