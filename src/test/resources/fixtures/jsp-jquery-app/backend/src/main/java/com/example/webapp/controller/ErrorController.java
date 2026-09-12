package com.example.webapp.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * Controller for error pages — never linked from the application's own navigation.
 */
@Controller
@RequestMapping("/error")
public class ErrorController {

    @RequestMapping(value = "/{code}", method = RequestMethod.GET)
    public String domaine(@PathVariable final String code, final ModelMap modelMap) {
        return "errors/" + code;
    }
}
