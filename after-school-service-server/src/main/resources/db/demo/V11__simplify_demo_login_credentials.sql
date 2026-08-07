-- Demo-only convenience credentials. Production profiles never load db/demo.
-- Keep this as a new migration so databases that already applied V4 remain valid.

UPDATE sys_user
SET password_hash = '{bcrypt}$2y$10$z45mz6/iAGhpW/xaKaD5d.r6n1LEAAp.3JH4wcPHlxLcNiQUxRI0u'
WHERE id BETWEEN 10 AND 16
  AND username IN (
      'regulator',
      'admin',
      'school_admin',
      'teacher_wang',
      'parent_chen',
      'school_admin_2',
      'teacher_li',
      'parent_zhao'
  );

UPDATE sys_user
SET username = 'admin'
WHERE id = 10
  AND school_id IS NULL
  AND role_id = 1
  AND username = 'regulator';
