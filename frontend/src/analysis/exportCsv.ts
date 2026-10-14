export type CsvColumn={name:string;label:string}
export function analysisCsv(columns:CsvColumn[],rows:Record<string,unknown>[]):string{
  if(!columns.length)throw new Error('没有可导出的列')
  const cell=(value:unknown):string=>{
    if(value===null||value===undefined)return ''
    const raw=String(value)
    // Prevent spreadsheet applications from interpreting dataset strings as formulas.
    const safe=/^[\s\t\r\n]*[=+\-@]/.test(raw)?"'"+raw:raw
    return '"'+safe.replace(/"/g,'""')+'"'
  }
  return '\uFEFF'+[columns.map(c=>cell(c.label)).join(','),
    ...rows.map(row=>columns.map(c=>cell(row[c.name])).join(','))].join('\r\n')
}
