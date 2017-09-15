/*
 * Copyright (c) 2012-2017 Red Hat, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.che.api.deploy;

import com.google.inject.AbstractModule;
import org.eclipse.che.api.user.server.jpa.JpaPreferenceDao;
import org.eclipse.che.api.user.server.jpa.JpaUserDao;
import org.eclipse.che.api.user.server.spi.PreferenceDao;
import org.eclipse.che.api.user.server.spi.UserDao;
import org.eclipse.che.inject.DynaModule;
import org.eclipse.che.multiuser.api.permission.server.PermissionChecker;
import org.eclipse.che.multiuser.api.permission.server.PermissionCheckerImpl;
import org.eclipse.che.multiuser.keycloak.server.deploy.KeycloakModule;
import org.eclipse.che.security.PBKDF2PasswordEncryptor;
import org.eclipse.che.security.PasswordEncryptor;

/** @author Max Shaposhnik (mshaposhnik@codenvy.com) */
@DynaModule
public class KeycloakApiModule extends AbstractModule {
  @Override
  protected void configure() {
    install(new KeycloakModule());

    //User and profile - use profile from keycloak and other stuff is JPA
    bind(PasswordEncryptor.class).to(PBKDF2PasswordEncryptor.class);
    bind(UserDao.class).to(JpaUserDao.class);
    bind(PreferenceDao.class).to(JpaPreferenceDao.class);
    bind(PermissionChecker.class).to(PermissionCheckerImpl.class);
  }
}
