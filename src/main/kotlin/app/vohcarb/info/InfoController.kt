package app.vohcarb.info

import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class InfoResponse(val name: String, val apiVersion: Int)

@RestController
@RequestMapping("/api/v1")
class InfoController(@Value("\${spring.application.name}") private val appName: String) {

    @GetMapping("/info")
    fun info() = InfoResponse(name = appName, apiVersion = 1)
}
