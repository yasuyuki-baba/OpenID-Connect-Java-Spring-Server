<%@ tag language="java" pageEncoding="UTF-8"%>
<%@ taglib prefix="security"
	uri="http://www.springframework.org/security/tags"%>
<security:authorize access="hasRole('ROLE_ADMIN')">
	<li class="nav-header">Administrative</li>
	<li><a href="manage/#admin/clients">Manage Clients</a></li>
	<li><a href="manage/#admin/whitelists">Whitelisted Clients</a></li>
	<li><a href="manage/#admin/blacklist">Blacklisted Clients</a></li>
	<li><a href="manage/#admin/scope">System Scopes</a></li>
	<li class="divider"></li>
</security:authorize>
<li class="nav-header">Personal</li>
<li><a href="manage/#user/approved">Manage Sites</a></li>
<li><a href="manage/#user/tokens">Manage Active Tokens</a></li>
<li><a href="manage/#user/profile">Manage Profiles</a></li>
