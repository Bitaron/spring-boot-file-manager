# Java & Spring Boot baseline

file-manager targets Java 25 (current LTS) and Spring Boot 4.1.1 (current stable) independently, rather than mirroring the sibling `spring-boot-activity-log` project's Java 21 / Spring Boot 3.5.16 baseline. That sibling pinned Java 21 specifically to dodge a Lombok/JDK 25 incompatibility; Lombok fixed JDK 25 support in 1.18.40 (further patched in 1.18.42), so the constraint no longer applies to a project starting now. Pin Lombok to `1.18.42`+ accordingly.
