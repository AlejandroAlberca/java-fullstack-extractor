package com.example.webapp.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * Redirects the application root to the default page.
 */
@Controller
@RequestMapping("/")
public class DefaultController {

    public static final String PAGE_DOSSIER_LISTE = "dossier/liste";

    @RequestMapping(value = "", method = RequestMethod.GET)
    public String defaultPage() {
        return PAGE_DOSSIER_LISTE;
    }
}
