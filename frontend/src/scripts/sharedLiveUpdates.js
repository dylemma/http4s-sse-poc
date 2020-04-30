import { BroadcastChannel, createLeaderElection } from 'broadcast-channel'

const Bacon = require('baconjs') // it doesn't like being `import`ed for some reason

// EventSource is a native class, but older browsers don't have it
import { NativeEventSource, EventSourcePolyfill } from 'event-source-polyfill'
const EventSource = NativeEventSource || EventSourcePolyfill

export default Bacon.fromBinder(sink => {
	let myLastEventId = null
	const channel = new BroadcastChannel('shared-live-updates')
	const elector = createLeaderElection(channel)

	function eventHandler(mode) {
		return function(event) {
			const { data, type, lastEventId } = event
			myLastEventId = lastEventId
			console.log(`${mode} event:`, event)
			sink(new Bacon.Next({ type, data }))
		}
	}

	const followerEventHandler = eventHandler('follower')
	const leaderEventHandler = eventHandler('leader')

	// subscribe to broadcasted events from other tabs
	channel.addEventListener('message', followerEventHandler)

	// placeholder function that gets filled in if the current tab gets elected leader
	let closeHandler = () => {/* noop */}

	elector.awaitLeadership().then(() => {
		console.log('became leader. lastEventId is', myLastEventId)
		const sseUrl = (myLastEventId === null) ? '/api/sse' : `/api/sse?last-event-id=${myLastEventId}`
		const events = new EventSource(sseUrl)
		events.addEventListener('counter', event => {
			const { data, lastEventId, type } = event
			const message = { data, lastEventId, type }
			// broadcast the event to other tabs
			channel.postMessage(message)
			// fire the event to our `sink` function
			leaderEventHandler(message)
		})

		// fill in the closeHandler so that it stops the SSE stream and steps down as leader
		closeHandler = () => {
			events.close()
			elector.die().then(() => {/* noop */})
		}
	})

	return () => {
		// unsubscribe; remove our sink listener from the channel, and tear down the SSE
		channel.removeEventListener('message', followerEventHandler)
		closeHandler()
	}
})