<%@ page language="java" pageEncoding="UTF-8" %>
<%@ taglib prefix="sec" uri="http://www.springframework.org/security/tags" %>
<nav class="navbar">
  <ul class="nav">
    <li><a href="#" class="link-top">Dossiers</a>
      <ul class="dropdown-menu">
        <sec:authorize access="hasPermission('','DOSSIER')">
        <li><a href="${pageContext.request.contextPath}/view/dossier/liste">Liste des dossiers</a></li>
        </sec:authorize>
      </ul>
    </li>
  </ul>
</nav>
