package com.example.webapp.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * Controller for the Dossier module.
 */
@Controller
@RequestMapping("/view/dossier")
@PreAuthorize("hasPermission('', 'DOSSIER')")
public class DossierController {

    @RequestMapping(method = RequestMethod.GET)
    public String domaine(final ModelMap map) {
        return "dossier/liste";
    }

    @RequestMapping(value = "/{partie}", method = RequestMethod.GET)
    public String partie(@PathVariable final String partie, final ModelMap map) {
        return "dossier/" + partie;
    }
}
