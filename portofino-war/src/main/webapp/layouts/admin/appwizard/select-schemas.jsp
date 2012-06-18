<%@ page contentType="text/html;charset=UTF-8" language="java"
         pageEncoding="UTF-8"
%><%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"
%><%@ taglib prefix="stripes" uri="http://stripes.sourceforge.net/stripes-dynattr.tld"
%><%@ taglib prefix="mde" uri="/manydesigns-elements"
%><%@ taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt"
%><%@ taglib tagdir="/WEB-INF/tags" prefix="portofino" %>
<stripes:layout-render name="/skins/default/wizard-page.jsp">
    <jsp:useBean id="actionBean" scope="request" type="com.manydesigns.portofino.actions.admin.appwizard.ApplicationWizard"/>
    <stripes:layout-component name="pageTitle">
        <fmt:message key="appwizard.step2.title" />
    </stripes:layout-component>
    <stripes:layout-component name="contentHeader">
    </stripes:layout-component>
    <stripes:layout-component name="portletTitle">
        <fmt:message key="appwizard.step2.title" />
    </stripes:layout-component>
    <stripes:layout-component name="portletBody">
        <mde:sessionMessages />
        <h2><fmt:message key="appwizard.schemas.found" /></h2>
        <mde:write name="actionBean" property="schemasForm"/>
        <label class="mde-form-label" for="advanced_checkbox"><fmt:message key="appwizard.showAdvancedOptions" /></label>
        <input id="advanced_checkbox" type="checkbox" name="advanced" <%= actionBean.isAdvanced() ? "checked='checked'" : "" %> />
        <div style="display: none;">
            <input type="hidden" name="connectionProviderType" value="${actionBean.connectionProviderType}" />
            <mde:write name="actionBean" property="jndiCPForm"/>
            <mde:write name="actionBean" property="jdbcCPForm"/>
        </div>
    </stripes:layout-component>
    <stripes:layout-component name="contentFooter">
        <portofino:buttons list="select-schemas" cssClass="contentButton" />
    </stripes:layout-component>
</stripes:layout-render>