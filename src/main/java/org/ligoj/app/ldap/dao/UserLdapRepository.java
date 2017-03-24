package org.ligoj.app.ldap.dao;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.naming.Name;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.ModificationItem;
import javax.naming.ldap.LdapName;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.validator.internal.constraintvalidators.hv.EmailValidator;
import org.ligoj.app.api.CompanyOrg;
import org.ligoj.app.api.GroupOrg;
import org.ligoj.app.api.Normalizer;
import org.ligoj.app.api.SimpleUser;
import org.ligoj.app.api.SimpleUserOrg;
import org.ligoj.app.api.UserOrg;
import org.ligoj.app.iam.IUserRepository;
import org.ligoj.app.ldap.dao.LdapCacheRepository.LdapData;
import org.ligoj.app.plugin.id.LdapUtils;
import org.ligoj.app.plugin.id.model.CompanyComparator;
import org.ligoj.app.plugin.id.model.FirstNameComparator;
import org.ligoj.app.plugin.id.model.LastNameComparator;
import org.ligoj.app.plugin.id.model.LoginComparator;
import org.ligoj.app.plugin.id.model.MailComparator;
import org.ligoj.bootstrap.core.DateUtils;
import org.ligoj.bootstrap.core.json.InMemoryPagination;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.ldap.core.DirContextAdapter;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.AbstractContextMapper;
import org.springframework.ldap.filter.AndFilter;
import org.springframework.ldap.filter.EqualsFilter;
import org.springframework.security.authentication.encoding.LdapShaPasswordEncoder;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * User LDAP repository
 */
@Slf4j
public class UserLdapRepository implements IUserRepository {

	/**
	 * Shared Random instance.
	 */
	private static final Random RANDOM = new SecureRandom();

	/**
	 * LDAP class filter.
	 */
	public static final String OBJECT_CLASS = "objectClass";

	private static final Map<String, Comparator<UserOrg>> COMPARATORS = new HashMap<>();

	/**
	 * User comparator for ordering
	 */
	public static final Comparator<UserOrg> DEFAULT_COMPARATOR = new LoginComparator();
	private static final Sort.Order DEFAULT_ORDER = new Sort.Order(Direction.ASC, "id");

	@Setter
	@Getter
	private LdapTemplate template;

	/**
	 * UID attribute name.
	 */
	@Setter
	private String uid = "sAMAccountName";

	/**
	 * Employee number attribute
	 */
	@Setter
	private String departmentAttribute = "employeeNumber";

	/**
	 * Local UID attribute name.
	 */
	@Setter
	private String localIdAttribute = "employeeID";

	/**
	 * Base DN for internal people. Should be a subset of people, so including {@link #peopleBaseDn}
	 */
	@Setter
	@Getter
	private String peopleInternalBaseDn;

	/**
	 * Object class of people
	 */
	@Setter
	private String peopleClass = "inetOrgPerson";

	/**
	 * Base DN for people.
	 */
	@Setter
	private String peopleBaseDn;

	/**
	 * Compiled pattern capturing the company from the DN of the user. May be a row string for constant.
	 */
	private Pattern companyPattern = Pattern.compile("");

	/**
	 * Special company that will contains the isolated accounts.
	 */
	@Setter
	private String quarantineBaseDn;

	/**
	 * LDAP Attribute used to tag a locked user. This attribute will contains several serialized values such as
	 * #lockedValue, author, date and previous company when this user is in the isolate state.<br>
	 * The structure of this attribute is composed by several fragments with pipe "|" as separator.
	 * The whole structure is :
	 * <code>FLAG|locked date as milliseconds|author|[optional old company for restore]</code>.
	 * 
	 * @see #lockedValue
	 */
	@Setter
	private String lockedAttribute;

	/**
	 * LDAP Attribute value to tag a disabled user.
	 * 
	 * @see #lockedAttribute
	 */
	@Setter
	private String lockedValue;

	@Autowired
	private InMemoryPagination inMemoryPagination;

	@Setter
	private GroupLdapRepository groupLdapRepository;

	@Getter
	@Setter
	private CompanyLdapRepository companyRepository;

	@Autowired
	private LdapCacheRepository ldapCacheRepository;

	/**
	 * LDAP Mapper
	 */
	private final Mapper mapper = new Mapper();

	static {
		COMPARATORS.put("company", new CompanyComparator());
		COMPARATORS.put("id", new LoginComparator());
		COMPARATORS.put("firstName", new FirstNameComparator());
		COMPARATORS.put("lastName", new LastNameComparator());
		COMPARATORS.put("mail", new MailComparator());
	}

	@Override
	public UserOrg create(final UserOrg entry) {
		// Build the DN
		final Name dn = buildDn(entry);

		// Create the LDAP entry
		entry.setDn(dn.toString());
		final DirContextAdapter context = new DirContextAdapter(dn);
		context.setAttributeValues(OBJECT_CLASS, new String[] { peopleClass });
		mapToContext(entry, context);
		template.bind(context);

		// Also, update the cache
		ldapCacheRepository.create(entry);

		// Return the original entry with updated DN
		return entry;
	}

	/**
	 * Replace a value by another one without touching other values.
	 * 
	 * @param dn
	 *            the DN of entry.
	 * @param attribute
	 *            The attribute name, single value.
	 * @param value
	 *            the new value.
	 */
	public void set(final Name dn, final String attribute, final String value) {
		final ModificationItem[] mods = new ModificationItem[1];
		mods[0] = new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute(attribute, value));
		template.modifyAttributes(org.springframework.ldap.support.LdapUtils.newLdapName(dn), mods);
	}

	/**
	 * Replace a value by another one without touching other values.
	 * 
	 * @param user
	 *            The entry to update.
	 * @param attribute
	 *            The attribute name, single value.
	 * @param value
	 *            the new value.
	 */
	public void set(final UserOrg user, final String attribute, final String value) {
		set(org.springframework.ldap.support.LdapUtils.newLdapName(user.getDn()), attribute, value);
	}

	@Override
	public UserOrg findByIdNoCache(final String login) {
		return findOneBy(uid, login);
	}

	@Override
	public List<UserOrg> findAllBy(final String attribute, final String value) {
		final AndFilter filter = new AndFilter();
		filter.and(new EqualsFilter(OBJECT_CLASS, peopleClass));
		filter.and(new EqualsFilter(attribute, value));
		return template.search(peopleBaseDn, filter.encode(), mapper).stream().map(u -> Optional.ofNullable(findById(u.getId())).orElse(u))
				.collect(Collectors.toList());
	}

	@Override
	@SuppressWarnings("unchecked")
	public Map<String, UserOrg> findAll() {
		return (Map<String, UserOrg>) ldapCacheRepository.getLdapData().get(LdapData.USER);
	}

	/**
	 * Return all user entries.
	 * 
	 * @param groups
	 *            The existing groups. They will be be used to complete the membership of each returned user.
	 * @return all user entries. Key is the user login.
	 */
	public Map<String, UserOrg> findAllNoCache(final Map<String, GroupOrg> groups) {

		// Fetch users and their direct attributes
		final List<UserOrg> users = template.search(peopleBaseDn, new EqualsFilter(OBJECT_CLASS, peopleClass).encode(), mapper);

		// INdex the users by the identifier
		final Map<String, UserOrg> result = new HashMap<>();
		for (final UserOrg user : users) {
			user.setGroups(new ArrayList<>());
			result.put(user.getId(), user);
		}

		// Update the memberships of this user
		for (final Entry<String, GroupOrg> groupEntry : groups.entrySet()) {
			updateMembership(result, groupEntry);
		}
		return result;
	}

	/**
	 * Update the membership of given group. All users are checked.
	 */
	private void updateMembership(final Map<String, UserOrg> result, final Entry<String, GroupOrg> groupEntry) {
		final GroupOrg groupLdap = groupEntry.getValue();
		final String group = groupLdap.getId();
		new ArrayList<>(groupLdap.getMembers()).forEach(dn -> {
			// Extract the UID
			final String uid = LdapUtils.toRdn(dn);

			// Remove this DN from the members, it would be replaced by the RDN form
			groupLdap.getMembers().remove(dn);

			// Check the broken UID reference
			final UserOrg user = result.get(uid);
			if (user == null) {
				if (!dn.startsWith(GroupLdapRepository.DEFAULT_MEMBER_DN)) {
					// It is a real broken reference
					log.warn("Broken user UID reference found '{}' --> {}", groupLdap.getDn(), uid);
				}
			} else {
				if (!Normalizer.normalize(dn).equals(Normalizer.normalize(user.getDn()))) {
					log.warn("Broken user DN reference found '{}' --> {}, instead of {}", groupLdap.getDn(), dn, user.getDn());
				}
				user.getGroups().add(group);

				// Finally, add the RDN (UID) of this user to replace the
				groupLdap.getMembers().add(uid);
			}
		});
	}

	@Override
	public String toDn(UserOrg newUser) {
		return buildDn(newUser).toString();
	}

	/**
	 * Return DN from entry.
	 * 
	 * @param entry
	 *            LDAP entry to convert to DN.
	 * @return DN from entry.
	 */
	public Name buildDn(final UserOrg entry) {
		return org.springframework.ldap.support.LdapUtils.newLdapName(buildDn(entry.getId(), companyRepository.findById(entry.getCompany()).getDn()));
	}

	/**
	 * Return DN from entry.
	 * 
	 * @param login
	 *            The user login to create.
	 * @param companyDn
	 *            The target company DN.
	 * @return DN from entry.
	 */
	private String buildDn(final String login, final String companyDn) {
		return "uid=" + login + "," + companyDn;
	}

	protected void mapToContext(final UserOrg entry, final DirContextOperations context) {
		context.setAttributeValue("cn", entry.getFirstName() + " " + entry.getLastName());
		context.setAttributeValue("sn", entry.getLastName());
		context.setAttributeValue("givenName", entry.getFirstName());
		context.setAttributeValue(uid, Normalizer.normalize(entry.getId()));
		context.setAttributeValues("mail", entry.getMails().toArray(), true);

		// Special and also optional attributes
		Optional.ofNullable(departmentAttribute).ifPresent(a -> context.setAttributeValue(a, entry.getDepartment()));
		Optional.ofNullable(localIdAttribute).ifPresent(a -> context.setAttributeValue(a, entry.getLocalId()));
	}

	private class Mapper extends AbstractContextMapper<UserOrg> {
		@Override
		public UserOrg doMapFromContext(final DirContextOperations context) {
			final UserOrg user = new UserOrg();
			user.setDn(context.getDn().toString());
			user.setLastName(context.getStringAttribute("sn"));
			user.setFirstName(context.getStringAttribute("givenName"));
			user.setSecured(context.getObjectAttribute("userPassword") != null);
			user.setId(Normalizer.normalize(context.getStringAttribute(uid)));

			// Special and also optional attributes
			Optional.ofNullable(departmentAttribute).ifPresent(a -> user.setDepartment(context.getStringAttribute(a)));
			Optional.ofNullable(localIdAttribute).ifPresent(a -> user.setLocalId(context.getStringAttribute(a)));
			Optional.ofNullable(lockedAttribute).ifPresent(a -> fillLockedData(user, context.getStringAttribute(a)));

			// Save the normalized CN of the company
			user.setCompany(toCompany(user.getDn()));

			// Save the mails
			user.setMails(new ArrayList<>(CollectionUtils.emptyIfNull(context.getAttributeSortedStringSet("mail"))));
			return user;
		}

		/**
		 * Extract the {@link Date}, author, and the previous company from the locked attribute if available and matched
		 * to the
		 * expected {@link UserLdapRepository#lockedValue}
		 * 
		 * @param user
		 *            The user to update.
		 * @param lockedValue
		 *            The locked value flag. May be <code>null</code>.
		 */
		private void fillLockedData(final SimpleUserOrg user, final String lockedValue) {
			if (StringUtils.startsWith(lockedValue, UserLdapRepository.this.lockedValue)) {
				// A locked account
				final String[] fragments = StringUtils.splitPreserveAllTokens(lockedValue, '|');
				user.setLocked(new Date(Long.parseLong(fragments[1])));
				user.setLockedBy(fragments[2]);
				user.setIsolated(StringUtils.defaultIfEmpty(fragments[3], null));
			}
		}
	}

	/**
	 * Extract the company from the DN of this user.
	 * 
	 * @param dn
	 *            The user DN.
	 * @return The company identifier from the DN of the user.
	 */
	protected String toCompany(final String dn) {
		final Matcher matcher = companyPattern.matcher(Normalizer.normalize(dn));
		if (matcher.matches()) {
			if (matcher.groupCount() > 0) {
				return Normalizer.normalize(matcher.group(1));
			}
			// Pattern match, but there is no capturing group
			return null;
		}

		// No matches
		if (matcher.groupCount() > 0) {
			// There is a capturing group but did not succeed
			return null;
		}
		// Constant form
		return Normalizer.normalize(companyPattern.pattern());
	}

	@Override
	public Page<UserOrg> findAll(final Collection<GroupOrg> requiredGroups, final Set<String> companies, final String criteria,
			final Pageable pageable) {
		// Create the set with the right comparator
		final List<Sort.Order> orders = IteratorUtils.toList(ObjectUtils.defaultIfNull(pageable.getSort(), new ArrayList<Sort.Order>()).iterator());
		orders.add(DEFAULT_ORDER);
		final Sort.Order order = orders.get(0);
		Comparator<UserOrg> comparator = ObjectUtils.defaultIfNull(COMPARATORS.get(order.getProperty()), DEFAULT_COMPARATOR);
		if (order.getDirection() == Direction.DESC) {
			comparator = Collections.reverseOrder(comparator);
		}
		final Set<UserOrg> result = new TreeSet<>(comparator);

		// Filter the users traversing firstly the required groups and their members, the companies, then the criteria
		final Map<String, UserOrg> users = findAll();
		if (requiredGroups == null) {
			// No constraint on group
			addFilteredByCompaniesAndPattern(users.keySet(), companies, criteria, result, users);
		} else {
			// User must be within one the given groups
			for (final GroupOrg requiredGroup : requiredGroups) {
				addFilteredByCompaniesAndPattern(requiredGroup.getMembers(), companies, criteria, result, users);
			}
		}

		// Apply in-memory pagination
		return inMemoryPagination.newPage(result, pageable);
	}

	/**
	 * Add the members to the result if they match to the required company and the pattern.
	 */
	private void addFilteredByCompaniesAndPattern(final Set<String> members, final Set<String> companies, final String criteria,
			final Set<UserOrg> result, final Map<String, UserOrg> users) {
		// Filter by company for each members
		for (final String member : members) {
			final UserOrg userLdap = users.get(member);

			// User is always found since #findAll() ensure the members of the groups exist
			addFilteredByCompaniesAndPattern(companies, criteria, result, userLdap);
		}

	}

	private void addFilteredByCompaniesAndPattern(final Set<String> companies, final String criteria, final Set<UserOrg> result,
			final UserOrg userLdap) {
		final List<CompanyOrg> userCompanies = companyRepository.findAll().get(userLdap.getCompany()).getCompanyTree();
		if (userCompanies.stream().map(CompanyOrg::getId).anyMatch(companies::contains)) {
			addFilteredByPattern(criteria, result, userLdap);
		}
	}

	private void addFilteredByPattern(final String criteria, final Set<UserOrg> result, final UserOrg userLdap) {
		if (criteria == null || matchPattern(userLdap, criteria)) {
			// Company and pattern match
			result.add(userLdap);
		}
	}

	/**
	 * Indicates the given user match to the given pattern.
	 */
	private boolean matchPattern(final UserOrg userLdap, final String criteria) {
		return StringUtils.containsIgnoreCase(userLdap.getFirstName(), criteria) || StringUtils.containsIgnoreCase(userLdap.getLastName(), criteria)
				|| StringUtils.containsIgnoreCase(userLdap.getId(), criteria)
				|| !userLdap.getMails().isEmpty() && StringUtils.containsIgnoreCase(userLdap.getMails().get(0), criteria);
	}

	@Override
	public void updateMembership(final Collection<String> groups, final UserOrg user) {
		// Add new groups
		addUserToGroups(user, CollectionUtils.subtract(groups, user.getGroups()));

		// Remove old groups
		removeUserFromGroups(user, CollectionUtils.subtract(user.getGroups(), groups));
	}

	/**
	 * Add the user from the given groups.Cache is also updated.
	 * 
	 * @param groups
	 *            the groups to add, normalized.
	 */
	protected void addUserToGroups(final UserOrg user, final Collection<String> groups) {
		for (final String group : groups) {
			groupLdapRepository.addUser(user, group);
		}
	}

	/**
	 * Remove the user from the given groups.Cache is also updated.
	 */
	protected void removeUserFromGroups(final UserOrg user, final Collection<String> groups) {
		for (final String group : groups) {
			groupLdapRepository.removeUser(user, group);
		}
	}

	@Override
	public void updateUser(final UserOrg user) {
		final DirContextOperations context = template.lookupContext(org.springframework.ldap.support.LdapUtils.newLdapName(user.getDn()));
		mapToContext(user, context);
		template.modifyAttributes(context);

		// Also, update the cache
		final UserOrg userLdap = findById(user.getId());
		user.copy((SimpleUser) userLdap);
		userLdap.setMails(user.getMails());

		ldapCacheRepository.update(user);
	}

	@Override
	public void delete(final UserOrg user) {
		final Name userDn = org.springframework.ldap.support.LdapUtils.newLdapName(user.getDn());

		// Delete the user from LDAP
		template.unbind(userDn);

		// Remove user from all groups
		removeUserFromGroups(user, user.getGroups());

		// Remove the user from the cache
		ldapCacheRepository.delete(user);
	}

	@Override
	public void lock(final String principal, final UserOrg user) {
		lock(principal, user, false);
	}

	@Override
	public void isolate(final String principal, final UserOrg user) {
		if (user.getIsolated() == null) {
			// Not yet isolated
			lock(principal, user, true);
			final String previousCompany = user.getCompany();
			move(user, companyRepository.findById(companyRepository.getQuarantineCompany()));
			user.setIsolated(previousCompany);
		}
	}

	@Override
	public void restore(final UserOrg user) {
		if (user.getIsolated() != null) {
			move(user, companyRepository.findById(user.getIsolated()));
			user.setIsolated(null);
			unlock(user);
		}
	}

	@Override
	public void move(final UserOrg user, final CompanyOrg company) {
		final LdapName newDn = org.springframework.ldap.support.LdapUtils.newLdapName(buildDn(user.getId(), company.getDn()));
		final LdapName oldDn = org.springframework.ldap.support.LdapUtils.newLdapName(user.getDn());
		template.rename(oldDn, newDn);
		user.setDn(newDn.toString());
		user.setCompany(company.getId());
		ldapCacheRepository.update(user);

		// Also, update the groups of this user
		user.getGroups().forEach(g -> groupLdapRepository.updateMemberDn(g, oldDn.toString(), newDn.toString()));
	}

	/**
	 * Lock an user :
	 * <ul>
	 * <li>Clear the password to prevent new authentication</li>
	 * <li>Set the disabled flag.</li>
	 * </ul>
	 * 
	 * @param principal
	 *            User requesting the lock.
	 * @param user
	 *            The LDAP user to disable.
	 * @param isolate
	 *            When <code>true</code>, the user will be isolated in addition.
	 */
	private void lock(final String principal, final UserOrg user, final boolean isolate) {
		if (user.getLockedBy() == null) {
			// Not yet locked
			final ModificationItem[] mods = new ModificationItem[2];
			final long timeInMillis = DateUtils.newCalendar().getTimeInMillis();
			mods[0] = new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute(lockedAttribute,
					String.format("%s|%s|%s|%s|", lockedValue, timeInMillis, principal, isolate ? user.getCompany() : "")));
			mods[1] = new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("userPassword", null));
			template.modifyAttributes(org.springframework.ldap.support.LdapUtils.newLdapName(user.getDn()), mods);

			// Also update the disabled date
			user.setLocked(new Date(timeInMillis));
			user.setLockedBy(principal);
		}
	}

	@Override
	public void unlock(final UserOrg user) {
		if (user.getIsolated() == null && user.getLockedBy() != null) {
			// Need to be unlocked
			final ModificationItem[] mods = new ModificationItem[1];
			mods[0] = new ModificationItem(DirContext.REMOVE_ATTRIBUTE, new BasicAttribute(lockedAttribute));
			template.modifyAttributes(org.springframework.ldap.support.LdapUtils.newLdapName(user.getDn()), mods);

			// Also clear the disabled state from cache
			user.setLocked(null);
			user.setLockedBy(null);
		}
	}

	@Override
	public boolean authenticate(final String name, final String password) {
		log.info("Authenticating {} ...", name);
		final String property = getAuthenticateProperty(name);
		final AndFilter filter = new AndFilter().and(new EqualsFilter("objectclass", peopleClass)).and(new EqualsFilter(property, name));
		final boolean result = template.authenticate(peopleBaseDn, filter.encode(), password);
		log.info("Authenticate {} : {}", name, result);
		return result;
	}

	/**
	 * Return the property name used to match the user name.
	 * 
	 * @param name
	 *            The current principal.
	 * @return the property name used to match the user name.
	 */
	public String getAuthenticateProperty(final String name) {
		return new EmailValidator().isValid(name, null) ? "mail" : uid;
	}

	@Override
	public String getToken(final String login) {
		final AndFilter filter = new AndFilter();
		filter.and(new EqualsFilter(OBJECT_CLASS, peopleClass));
		filter.and(new EqualsFilter(uid, login));
		return template.search(peopleBaseDn, filter.encode(), new AbstractContextMapper<String>() {
			@Override
			public String doMapFromContext(final DirContextOperations context) {
				// Get the password
				return new String(ObjectUtils.defaultIfNull((byte[]) context.getObjectAttribute("userPassword"), new byte[0]),
						StandardCharsets.UTF_8);
			}
		}).stream().findFirst().orElse(null);
	}

	/**
	 * Validate and set the company pattern.
	 * 
	 * @param companyPattern
	 *            Pattern capturing the company from the DN of the user. May be a row string for constant.
	 */
	public void setCompanyPattern(final String companyPattern) {
		this.companyPattern = Pattern.compile(companyPattern);
	}

	/**
	 * Digest with SSHA the given clear password.
	 * 
	 * @param password
	 *            the clear password to digest.
	 * @return a SSHA digest.
	 */
	private String digest(final String password) {
		final byte[] bytes = new byte[4];
		RANDOM.nextBytes(bytes);
		return new LdapShaPasswordEncoder().encodePassword(password, bytes);
	}

	@Override
	public void setPassword(final UserOrg userLdap, final String password) {
		set(userLdap, "userPassword", digest(password));
	}
}
