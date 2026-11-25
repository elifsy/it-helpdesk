export interface PersonOption {
  id: number
  name: string
  department?: string | null
  deptName?: string | null
  employeeNo?: string | null
  skill?: string | null
  description?: string | null
  namePinyin?: readonly (readonly string[])[]
}

const normalized = (value: string) => value.normalize('NFKC').toLowerCase()

function phoneticTerm(value: string): string {
  const spelling = value.normalize('NFD').replace(/\p{M}/gu, '').replace(/v|u:/g, 'u').replace(/['’]/g, '')
  return /^[a-z]+$/.test(spelling) ? spelling : ''
}

function phoneticMatch(pinyin: NonNullable<PersonOption['namePinyin']>, query: string, initials: boolean): boolean {
  let offsets = new Set([0])
  for (const readings of pinyin) {
    const next = new Set([0])
    for (const offset of offsets) {
      const remaining = query.slice(offset)
      for (const reading of readings) {
        const part = initials ? reading.slice(0, 1) : reading
        if (part.startsWith(remaining)) return true
        if (remaining.startsWith(part)) next.add(offset + part.length)
      }
    }
    offsets = next
  }
  return false
}

export function personDetails(person: PersonOption): string {
  return [person.department || person.deptName, person.employeeNo, person.skill, person.description]
    .filter(Boolean).join(' · ')
}

export function matchingPeople(people: readonly PersonOption[], value: string): PersonOption[] {
  const query = normalized(value).trim()
  const terms = query.split(/\s+/).filter(Boolean).map(term => ({ term, spelling: phoneticTerm(term) }))
  const matches = people.filter(person => {
    const text = normalized(person.name + ' ' + personDetails(person))
    const pinyin = person.namePinyin ?? []
    return terms.every(({ term, spelling }) => text.includes(term) || (spelling && (
      phoneticMatch(pinyin, spelling, false) || phoneticMatch(pinyin, spelling, true)
    )))
  })
  if (!query) return matches
  const rank = (person: PersonOption) => {
    const name = normalized(person.name)
    return name === query ? 0 : name.startsWith(query) ? 1 : name.includes(query) ? 2 : 3
  }
  return matches.sort((a, b) => rank(a) - rank(b))
}
