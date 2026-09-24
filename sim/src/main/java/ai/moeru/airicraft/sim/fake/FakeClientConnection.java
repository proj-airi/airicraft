package ai.moeru.airicraft.sim.fake;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.packet.Packet;
import net.minecraft.text.Text;

/** Inert serverbound connection for fake players: no channel, no sends, no disconnects. */
public final class FakeClientConnection extends ClientConnection {
	public FakeClientConnection() {
		super(NetworkSide.SERVERBOUND);
	}

	@Override
	public void channelActive(ChannelHandlerContext context) {
		// No real channel exists; never mark the connection open via netty callbacks.
	}

	@Override
	public void channelInactive(ChannelHandlerContext context) {
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext context, Throwable throwable) {
	}

	@Override
	public <T extends net.minecraft.network.listener.PacketListener> void transitionInbound(
			net.minecraft.network.state.NetworkState<T> state, T packetListener) {
		// Protocol transitions normally write a packet to the channel; nothing to send.
	}

	@Override
	public void transitionOutbound(net.minecraft.network.state.NetworkState<?> state) {
	}

	@Override
	public void submit(java.util.function.Consumer<ClientConnection> consumer) {
		// Default submits work to the (missing) channel's event loop; run inline instead.
		consumer.accept(this);
	}

	@Override
	public void send(Packet<?> packet) {
	}

	@Override
	public void send(Packet<?> packet, ChannelFutureListener listener) {
	}

	@Override
	public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
	}

	@Override
	public void flush() {
	}

	@Override
	public void tick() {
	}

	@Override
	public void disconnect(Text reason) {
	}

	@Override
	public void disconnect(DisconnectionInfo info) {
	}

	@Override
	public void handleDisconnection() {
		// Keep the fake session alive; disconnects are driven explicitly.
	}

	@Override
	public boolean isLocal() {
		return true;
	}
}
