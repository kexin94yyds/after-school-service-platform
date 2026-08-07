package com.afterschool.platform.auth;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class PlatformUserDetailsService implements UserDetailsService {

    private final UserAccountMapper mapper;

    public PlatformUserDetailsService(UserAccountMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserAccount account = mapper.findByUsername(username.strip());
        if (account == null) {
            throw new UsernameNotFoundException("用户不存在");
        }
        boolean invalidScopedIdentity =
                ("REGULATOR".equals(account.getRoleCode()) && account.getSchoolId() != null)
                        || (!"REGULATOR".equals(account.getRoleCode()) && account.getSchoolId() == null)
                        || ("TEACHER".equals(account.getRoleCode()) && account.getTeacherId() == null)
                        || ("GUARDIAN".equals(account.getRoleCode()) && account.getGuardianId() == null);
        if (invalidScopedIdentity) {
            throw new UsernameNotFoundException("账号身份档案不完整");
        }
        return new PlatformPrincipal(account);
    }
}
