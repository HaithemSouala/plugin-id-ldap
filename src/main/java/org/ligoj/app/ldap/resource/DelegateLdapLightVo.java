package org.ligoj.app.ldap.resource;

import lombok.Getter;
import lombok.Setter;
import org.ligoj.bootstrap.core.INamableBean;
import org.ligoj.bootstrap.core.NamedAuditedBean;
import org.ligoj.app.api.SimpleUserLdap;
import org.ligoj.app.model.DelegateLdapType;
import org.ligoj.app.model.ReceiverType;

/**
 * LDAP delegation business object for display.
 */
@Getter
@Setter
public class DelegateLdapLightVo extends NamedAuditedBean<SimpleUserLdap, Integer> {

	/**
	 * The people receiving the delegate.
	 */
	private INamableBean<String> receiver;

	/**
	 * The type of people receiving this delegate.
	 */
	private ReceiverType receiverType;

	/**
	 * The delegate type.
	 */
	private DelegateLdapType type;

	/**
	 * Can write with this delegate.
	 */
	private boolean canWrite;

	/**
	 * Can manage all delegation within the same scope.
	 */
	private boolean canAdmin;

	/**
	 * This entry can be managed by the current user.
	 */
	private boolean managed;

}
