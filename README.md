# Experimental yet effective YAML to DML converter, written on Kotlin

****

Allows to convert all the Spring application property files (aka application.yaml) to a DML which afterwards can be used as a changeset for DB migration (flyway, liquibase)

Effective for implementing **Spring Cloud Config Server** (JDBC implementation in particular) in your projects

It is able to group common (equal) properties across all applications, as well as it also distinguishes different spring profiles used in YAML configuration files

**SnakeYAML** is the only 3rd party dependency that is used to parse YAML files

****

Do not mind awful coding, I wrote this utility when I was only starting learning Kotlin (early 2022) :P