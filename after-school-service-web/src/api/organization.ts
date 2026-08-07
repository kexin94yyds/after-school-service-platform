import { http } from './http'
import type {
  Guardian,
  GuardianInput,
  School,
  SchoolAdminAccount,
  SchoolAdminInput,
  SchoolClass,
  SchoolClassInput,
  SchoolInput,
  Student,
  StudentInput,
  Teacher,
  TeacherInput,
} from './types'

async function getAll<T>(path: string): Promise<T[]> {
  const response = await http.get<T[]>(path)
  return response.data
}

async function create<T, P>(path: string, payload: P): Promise<T> {
  const response = await http.post<T>(path, payload)
  return response.data
}

async function update<T, P>(
  path: string,
  id: number,
  payload: P,
): Promise<T> {
  const response = await http.put<T>(`${path}/${id}`, payload)
  return response.data
}

export const organizationApi = {
  getSchools: () => getAll<School>('/schools'),
  createSchool: (payload: SchoolInput) =>
    create<School, SchoolInput>('/schools', payload),
  updateSchool: ({ id, ...payload }: SchoolInput & { id: number }) =>
    update<School, SchoolInput>('/schools', id, payload),
  getSchoolAdmins: (schoolId: number) =>
    getAll<SchoolAdminAccount>(`/schools/${schoolId}/admins`),
  createSchoolAdmin: (schoolId: number, payload: SchoolAdminInput) =>
    create<SchoolAdminAccount, SchoolAdminInput>(
      `/schools/${schoolId}/admins`,
      payload,
    ),
  updateSchoolAdmin: (
    schoolId: number,
    { id, ...payload }: SchoolAdminInput & { id: number },
  ) =>
    update<SchoolAdminAccount, SchoolAdminInput>(
      `/schools/${schoolId}/admins`,
      id,
      payload,
    ),

  getClasses: () => getAll<SchoolClass>('/classes'),
  createClass: (payload: SchoolClassInput) =>
    create<SchoolClass, SchoolClassInput>('/classes', payload),
  updateClass: ({ id, ...payload }: SchoolClassInput & { id: number }) =>
    update<SchoolClass, SchoolClassInput>('/classes', id, payload),

  getTeachers: () => getAll<Teacher>('/teachers'),
  createTeacher: (payload: TeacherInput) =>
    create<Teacher, TeacherInput>('/teachers', payload),
  updateTeacher: ({ id, ...payload }: TeacherInput & { id: number }) =>
    update<Teacher, TeacherInput>('/teachers', id, payload),

  getStudents: () => getAll<Student>('/students'),
  createStudent: (payload: StudentInput) =>
    create<Student, StudentInput>('/students', payload),
  updateStudent: ({ id, ...payload }: StudentInput & { id: number }) =>
    update<Student, StudentInput>('/students', id, payload),

  getGuardians: () => getAll<Guardian>('/guardians'),
  createGuardian: (payload: GuardianInput) =>
    create<Guardian, GuardianInput>('/guardians', payload),
  updateGuardian: ({ id, ...payload }: GuardianInput & { id: number }) =>
    update<Guardian, GuardianInput>('/guardians', id, payload),
}
