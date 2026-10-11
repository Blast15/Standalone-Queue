import mc from 'minecraft-protocol'
import net from 'node:net'
import readline from 'node:readline'

// JSON-lines RPC for a local staging orchestrator; no Microsoft credentials are requested/stored.
const clients = new Map()
const emit = (value) => process.stdout.write(JSON.stringify(value) + '\n')
readline.createInterface({ input: process.stdin }).on('line', (line) => {
  let request
  try {
    request = JSON.parse(line)
    if (request.action === 'close') {
      clients.get(request.id)?.end('staging-close')
      clients.delete(request.id)
      return
    }
    if (request.action === 'shutdown') {
      for (const client of clients.values()) client.end('staging-shutdown')
      setTimeout(() => process.exit(0), 100).unref()
      return
    }
    if (request.action !== 'connect' || typeof request.id !== 'string' || clients.has(request.id)) {
      throw new Error('Invalid action/id or duplicate connection id')
    }
    const address = request.localAddress || '127.0.0.1'
    if (!/^127\.(\d{1,3}\.){2}\d{1,3}$/.test(address) || !net.isIPv4(address)) {
      throw new Error('Staging source must be IPv4 loopback')
    }
    if (!/^[A-Za-z0-9_]{1,16}$/.test(request.name)) throw new Error('Invalid test player name')
    const port = request.port ?? 25576
    if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Invalid staging port')
    const client = mc.createClient({
      host: '127.0.0.1', port, username: request.name, version: '1.21.4', auth: 'offline',
      connect: (client) => client.setSocket(net.createConnection({ host: '127.0.0.1', port, localAddress: address }))
    })
    clients.set(request.id, client)
    client.once('login', () => emit({ id: request.id, event: 'play', uuid: client.uuid }))
    client.on('position', (packet) => {
      if (packet.teleportId !== undefined) client.write('teleport_confirm', { teleportId: packet.teleportId })
    })
    client.on('kick_disconnect', (packet) => emit({ id: request.id, event: 'kick', reason: packet.reason }))
    client.on('disconnect', (packet) => emit({ id: request.id, event: 'kick', reason: packet.reason }))
    client.once('error', (error) => emit({ id: request.id, event: 'error', error: error.message }))
    client.once('end', (reason) => {
      emit({ id: request.id, event: 'end', reason })
      clients.delete(request.id)
    })
  } catch (error) { emit({ id: request?.id, event: 'error', error: error.message }) }
})
