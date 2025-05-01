package cta

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import java.util.UUID

/**
 * The main entrypoint into the app application
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
class Application

/**
 * Bootstraps the application with the given command line [args]
 */
fun main(args: Array<String>) {
    val props = args.plus("--info.app.uuid=${UUID.randomUUID()}")
    print(props.toList())
    runApplication<Application>(*props)
}
