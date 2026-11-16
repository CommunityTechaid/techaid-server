package cta.controllers

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam

// Only exists while the form-login fallback is switched on (see SecurityConfig.filterChain).
@Controller
@ConditionalOnProperty(name = ["auth.form-login-enabled"], havingValue = "true")
class LoginController {
    @RequestMapping(value = ["/login"], method = [RequestMethod.GET])
    fun loginPage(
        @RequestParam(value = "error", required = false) error: String?,
        @RequestParam(value = "logout", required = false) logout: String?,
        @RequestParam(value = "local", required = false) local: String?,
        model: Model,
    ): String {
        error?.let {
            model.addAttribute("error", it)
        }

        logout?.let {
            model.addAttribute("message", it)
        }

        local?.let {
            model.addAttribute("local", it)
        }

        return "login"
    }
}
