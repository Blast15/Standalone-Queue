import {spawn} from 'node:child_process'
import assert from 'node:assert/strict'
import {fileURLToPath} from 'node:url'
const child = spawn(process.execPath, [fileURLToPath(new URL('clients.mjs', import.meta.url))], {stdio:['pipe','pipe','pipe']})
let output = '', errors = ''
child.stdout.on('data', data => output += data)
child.stderr.on('data', data => errors += data)
child.stdin.end(JSON.stringify({id:'guard',action:'connect',name:'Staging',localAddress:'8.8.8.8'})+'\n'+JSON.stringify({action:'shutdown'})+'\n')
await new Promise((resolve,reject) => {
  child.on('exit', code => code === 0 ? resolve() : reject(new Error(errors)))
  child.on('error', reject)
})
const result = JSON.parse(output.trim())
assert.equal(result.id, 'guard')
assert.equal(result.event, 'error')
assert.match(result.error, /loopback/)
console.log('Client loopback guard: PASS')
