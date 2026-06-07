import { PacketTypeEntryPayload, PacketTypeRegistryPayload } from "./_protocol";

export interface GamePacketDescriptor {
  readonly packetId: number;
  readonly name: string;
  readonly description: string;
}

export class GamePacketRegistry {
  private descriptors: GamePacketDescriptor[] = [];

  register(packetId: number, name: string, description = ""): void {
    if (packetId < 0x10) throw new Error(`packetId must be >= 0x10, got 0x${packetId.toString(16)}`);
    this.descriptors.push({ packetId, name, description });
  }

  buildRegistry(): PacketTypeRegistryPayload {
    const entries: PacketTypeEntryPayload[] = this.descriptors.map((d) => ({
      packetId: d.packetId,
      name: d.name,
      description: d.description,
    }));
    return { entries };
  }

  get isEmpty(): boolean {
    return this.descriptors.length === 0;
  }
}
